package com.nexzian;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.nexzian.entity.Lead;
import com.nexzian.entity.Property;
import com.nexzian.entity.PropertyImage;
import com.nexzian.entity.Tenant;
import com.nexzian.entity.User;
import com.nexzian.repository.LeadRepository;
import com.nexzian.repository.PropertyImageRepository;
import com.nexzian.repository.PropertyRepository;
import com.nexzian.repository.TenantRepository;
import com.nexzian.repository.UserRepository;
import com.nexzian.security.JwtService;
import com.nexzian.security.LoginAttemptLimiter;
import com.nexzian.security.RegistrationRateLimiter;

import jakarta.servlet.http.Cookie;

/**
 * Shared fixtures for the authorization / tenant-isolation regression suite (S6 / R1).
 *
 * Every test starts with two ACTIVE agencies, "Alpha" (the caller, tenant A) and "Beta"
 * (the victim, tenant B), each with an owner, properties and leads, plus an agent and a
 * staff member in Alpha and a platform administrator.
 *
 * Helpers capture the complete state of the database and of the upload directory so a
 * test can prove a rejected request changed NOTHING, and snapshot individual victim
 * records so it can prove they are unmodified.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AuthorizationTestSupport {

    protected static final String PASSWORD = "test-password";

    /** Text that appears only in tenant B's data: it must never show up in an A response. */
    protected static final String[] TENANT_B_MARKERS =
            { "Beta Villa", "Beta Draft", "Bob Beta", "9000000002", "beta-private-photo" };

    private static String cachedPasswordHash;

    @Autowired protected MockMvc mockMvc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected TenantRepository tenantRepository;
    @Autowired protected PropertyRepository propertyRepository;
    @Autowired protected LeadRepository leadRepository;
    @Autowired protected PropertyImageRepository imageRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JwtService jwtService;
    @Autowired private LoginAttemptLimiter loginAttemptLimiter;
    @Autowired private RegistrationRateLimiter registrationRateLimiter;
    @Autowired private DataSource dataSource;

    @Value("${nexzian.file.upload-dir}")
    private String uploadDir;

    /** Names of the files in the upload directory when the test's base fixtures were ready. */
    private Set<String> uploadFilesAtStart;

    protected Tenant tenantA;
    protected Tenant tenantB;
    protected User ownerA;
    protected User agentA;
    protected User staffA;
    protected User ownerB;
    protected User agentB;
    protected User platformAdmin;
    protected Property propertyA;
    protected Property propertyB;
    protected Property draftPropertyB;
    protected Lead leadA;
    protected Lead leadB;

    protected String ownerAToken;
    protected String agentAToken;
    protected String staffAToken;
    protected String ownerBToken;
    protected String platformAdminToken;

    // ---- fixture lifecycle ---------------------------------------------------------------

    @BeforeEach
    void createBaseFixtures() throws IOException {
        // MUST stay the first statement: nothing below may run against a real database or
        // outside the temp directory (it deletes every row and every uploaded file).
        assertSafeTestEnvironment();

        loginAttemptLimiter.clearAll();
        registrationRateLimiter.clearAll();
        deleteAllImagesAndFiles();
        leadRepository.deleteAll();
        propertyRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        tenantA = createTenant("Alpha Realty", "alpha-realty");
        tenantB = createTenant("Beta Homes", "beta-homes");

        ownerA = createUser("owner-a@example.com", tenantA.getId(), "OWNER");
        agentA = createUser("agent-a@example.com", tenantA.getId(), "AGENT");
        staffA = createUser("staff-a@example.com", tenantA.getId(), "STAFF");
        ownerB = createUser("owner-b@example.com", tenantB.getId(), "OWNER");
        agentB = createUser("agent-b@example.com", tenantB.getId(), "AGENT");
        platformAdmin = createUser("admin@example.com", new UUID(0L, 0L), "PLATFORM_ADMIN");

        ownerAToken = jwtService.generateToken(ownerA);
        agentAToken = jwtService.generateToken(agentA);
        staffAToken = jwtService.generateToken(staffA);
        ownerBToken = jwtService.generateToken(ownerB);
        platformAdminToken = jwtService.generateToken(platformAdmin);

        propertyA = saveProperty("Alpha Flat", tenantA.getId(), "PUBLISHED");
        propertyB = saveProperty("Beta Villa", tenantB.getId(), "PUBLISHED");
        draftPropertyB = saveProperty("Beta Draft", tenantB.getId(), "DRAFT");

        leadA = saveLead("Alice Alpha", "9000000001", tenantA.getId(), propertyA.getId());
        leadB = saveLead("Bob Beta", "9000000002", tenantB.getId(), propertyB.getId());

        // Baseline taken before any subclass uploads, so @AfterEach can prove this test left
        // no uploaded file behind (files that pre-date the test are not this test's concern).
        uploadFilesAtStart = uploadFileNames();
    }

    @AfterEach
    void removeUploadsAndLeads() throws IOException {
        // MUST stay the first statement (see createBaseFixtures).
        assertSafeTestEnvironment();

        // Other test classes call propertyRepository.deleteAll(); leftover image rows
        // (foreign key to properties) would make that fail, so always clean up here.
        deleteAllImagesAndFiles();
        leadRepository.deleteAll();

        Set<String> leftovers = uploadFileNames();
        leftovers.removeAll(uploadFilesAtStart);
        if (!leftovers.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail("This test left uploaded file(s) behind in "
                    + uploadPath() + ": " + leftovers);
        }
    }

    /**
     * Deletes every image row and its file. A file that cannot be deleted is NOT ignored:
     * the rows are still removed (so later tests are not blocked by the foreign key) but the
     * cleanup then fails, naming each affected path.
     */
    private void deleteAllImagesAndFiles() {
        Path root = requireUploadDirectoryInsideTempDir();
        List<String> failures = new ArrayList<>();

        try {
            for (PropertyImage image : imageRepository.findAll()) {
                Path file = root.resolve(image.getStoredFileName()).normalize();
                if (!file.startsWith(root)) {
                    failures.add(file + " (stored file name escapes the upload directory; left untouched)");
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    failures.add(file + " -> " + e);
                }
            }
        } finally {
            imageRepository.deleteAll();
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Test cleanup could not delete " + failures.size()
                    + " uploaded file(s): " + failures);
        }
    }

    // ---- destructive-cleanup guards ---------------------------------------------------------

    /**
     * Refuses to continue unless it is certain that deleting all rows and all uploaded files
     * is harmless: the datasource must be an IN-MEMORY H2 database and the upload directory
     * must be a strict sub-directory of java.io.tmpdir.
     */
    private void assertSafeTestEnvironment() {
        requireInMemoryH2();
        requireUploadDirectoryInsideTempDir();
    }

    private void requireInMemoryH2() {
        String product;
        String url;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            product = metaData.getDatabaseProductName();
            url = metaData.getURL();
        } catch (SQLException e) {
            throw new IllegalStateException("REFUSING destructive test cleanup: could not verify the "
                    + "datasource is an in-memory H2 database", e);
        }

        boolean inMemoryH2 = "H2".equals(product) && url != null
                && url.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:mem:");
        if (!inMemoryH2) {
            String shownUrl = url == null ? "null" : url.split("[;?]")[0]; // never print parameters
            throw new IllegalStateException("REFUSING destructive test cleanup: the datasource is not an "
                    + "in-memory H2 database (product=" + product + ", url=" + shownUrl + "). "
                    + "Check src/test/resources/application.properties.");
        }
    }

    /**
     * Returns the real (symlink-resolved, normalized) upload directory, after proving it is a
     * strict sub-directory of the real temp directory and contains no ".." segment.
     */
    private Path requireUploadDirectoryInsideTempDir() {
        try {
            Path configured = Paths.get(uploadDir);
            for (Path segment : configured) {
                if ("..".equals(segment.toString())) {
                    throw unsafeUploadDir("it contains a '..' path segment");
                }
            }

            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir")).toRealPath();
            if (tempDir.getParent() == null) {
                throw unsafeUploadDir("java.io.tmpdir resolves to a filesystem root (" + tempDir + ")");
            }

            Path uploadDirectory = realPathAllowingMissingTail(configured.toAbsolutePath().normalize());
            if (!uploadDirectory.startsWith(tempDir) || uploadDirectory.equals(tempDir)) {
                throw unsafeUploadDir("it resolves to " + uploadDirectory + ", which is not a sub-directory of "
                        + tempDir);
            }
            return uploadDirectory;
        } catch (IOException e) {
            throw new IllegalStateException("REFUSING destructive test cleanup: could not resolve the "
                    + "upload directory '" + uploadDir + "'", e);
        }
    }

    private IllegalStateException unsafeUploadDir(String reason) {
        return new IllegalStateException("REFUSING destructive test cleanup: nexzian.file.upload-dir='"
                + uploadDir + "' is unsafe because " + reason
                + ". It must be a sub-directory of java.io.tmpdir.");
    }

    /** toRealPath() of the deepest existing ancestor, with the not-yet-created tail appended. */
    private static Path realPathAllowingMissingTail(Path path) throws IOException {
        Path existing = path;
        List<Path> missing = new ArrayList<>();
        while (existing != null && !Files.exists(existing)) {
            missing.add(0, existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("no existing ancestor for " + path);
        }
        Path real = existing.toRealPath();
        for (Path tail : missing) {
            real = real.resolve(tail);
        }
        return real.normalize();
    }

    /** File names currently in the upload directory (sorted, for readable failure messages). */
    private Set<String> uploadFileNames() throws IOException {
        Path dir = requireUploadDirectoryInsideTempDir();
        if (!Files.isDirectory(dir)) {
            return new TreeSet<>();
        }
        try (Stream<Path> listing = Files.list(dir)) {
            Set<String> names = new TreeSet<>();
            listing.filter(Files::isRegularFile).forEach(p -> names.add(p.getFileName().toString()));
            return names;
        }
    }

    // ---- entity factories ---------------------------------------------------------------------

    protected Tenant createTenant(String name, String slug) {
        Tenant tenant = new Tenant();
        tenant.setBusinessName(name);
        tenant.setWebsiteSlug(slug);
        tenant.setEmail(slug + "@tenant.example");
        tenant.setCity("Hyderabad");
        return tenantRepository.save(tenant);
    }

    protected User createUser(String email, UUID tenantId, String role) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setFullName(email);
        user.setEmail(email);
        user.setPassword(passwordHash());
        user.setRole(role);
        user.setStatus("ACTIVE");
        return userRepository.save(user);
    }

    protected Property saveProperty(String title, UUID tenantId, String status) {
        Property property = new Property();
        property.setTenantId(tenantId);
        property.setTitle(title);
        property.setDescription("Description of " + title);
        property.setTransactionType("SALE");
        property.setPropertyCategory("RESIDENTIAL");
        property.setPropertyType("APARTMENT");
        property.setCity("Hyderabad");
        property.setPrice(new BigDecimal("100000"));
        property.setAreaSqft(new BigDecimal("1000"));
        property.setBedrooms(2);
        property.setBathrooms(2);
        property.setStatus(status);
        return propertyRepository.save(property);
    }

    protected Lead saveLead(String name, String phone, UUID tenantId, UUID propertyId) {
        Lead lead = new Lead();
        lead.setTenantId(tenantId);
        lead.setPropertyId(propertyId);
        lead.setCustomerName(name);
        lead.setPhone(phone);
        lead.setEmail(name.toLowerCase().replace(' ', '.') + "@customer.example");
        lead.setSource("WEBSITE");
        lead.setMessage("Interested in " + name);
        return leadRepository.save(lead);
    }

    protected void setTenantStatus(Tenant tenant, String status) {
        Tenant stored = tenantRepository.findById(tenant.getId()).orElseThrow();
        stored.setStatus(status);
        tenantRepository.saveAndFlush(stored);
    }

    private synchronized String passwordHash() {
        if (cachedPasswordHash == null) {
            cachedPasswordHash = passwordEncoder.encode(PASSWORD); // BCrypt once per JVM, not per user
        }
        return cachedPasswordHash;
    }

    // ---- HTTP helpers ----------------------------------------------------------------------------

    /** Sends a request with a valid CSRF token, so a 403 can only come from authorization. */
    protected ResultActions call(HttpMethod method, String path, String token, String jsonBody,
            Object... uriVariables) throws Exception {

        MockHttpServletRequestBuilder builder = request(method, path, uriVariables);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (jsonBody != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(jsonBody);
        }
        if (!HttpMethod.GET.equals(method)) {
            Cookie csrf = csrfCookie();
            builder.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
        return mockMvc.perform(builder);
    }

    protected ResultActions get(String path, String token, Object... vars) throws Exception {
        return call(HttpMethod.GET, path, token, null, vars);
    }

    protected ResultActions post(String path, String token, String body, Object... vars) throws Exception {
        return call(HttpMethod.POST, path, token, body, vars);
    }

    protected ResultActions put(String path, String token, String body, Object... vars) throws Exception {
        return call(HttpMethod.PUT, path, token, body, vars);
    }

    protected ResultActions delete(String path, String token, Object... vars) throws Exception {
        return call(HttpMethod.DELETE, path, token, null, vars);
    }

    protected ResultActions upload(String token, UUID propertyId, String fileName) throws Exception {
        Cookie csrf = csrfCookie();
        MockMultipartFile file = new MockMultipartFile("file", fileName, "image/png", new byte[] { 1, 2, 3, 4, 5 });
        var builder = multipart("/api/v1/properties/{id}/images", propertyId).file(file)
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(builder);
    }

    /** Uploads a real image through the API as the given owner and returns its id. */
    protected UUID uploadImage(String ownerToken, UUID propertyId, String fileName) throws Exception {
        MvcResult result = upload(ownerToken, propertyId, fileName).andExpect(status().isOk()).andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    protected Cookie csrfCookie() throws Exception {
        return mockMvc.perform(request(HttpMethod.GET, "/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
    }

    // ---- response assertions ----------------------------------------------------------------------

    /** The app answers "not in your tenant" with 404 (most routes) or 409 (image routes, finding L8). */
    protected ResultActions expectNotFoundForThisTenant(ResultActions result) throws Exception {
        return result.andExpect(status().is(anyOf(equalTo(404), equalTo(409))));
    }

    /** The response must not contain anything that belongs only to tenant B. */
    protected ResultActions expectNoTenantBData(ResultActions result) throws Exception {
        for (String marker : TENANT_B_MARKERS) {
            result.andExpect(content().string(not(containsString(marker))));
        }
        return result.andExpect(content().string(not(containsString(tenantB.getId().toString()))));
    }

    // ---- state / snapshots -------------------------------------------------------------------------------

    /** Counts of every tenant-owned table plus the number of files in the upload directory. */
    protected record State(long tenants, long users, long properties, long leads, long images, long files) { }

    protected State state() throws IOException {
        long files;
        try (Stream<Path> listing = Files.list(uploadPath())) {
            files = listing.filter(Files::isRegularFile).count();
        }
        return new State(tenantRepository.count(), userRepository.count(), propertyRepository.count(),
                leadRepository.count(), imageRepository.count(), files);
    }

    protected Path uploadPath() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    protected String propertySnapshot(UUID id) {
        Property p = propertyRepository.findById(id).orElseThrow();
        return String.join("|", String.valueOf(p.getId()), String.valueOf(p.getTenantId()), p.getTitle(),
                p.getDescription(), p.getTransactionType(), p.getPropertyCategory(), p.getPropertyType(),
                p.getCity(), String.valueOf(p.getLocality()), String.valueOf(p.getPrice()),
                String.valueOf(p.getAreaSqft()), String.valueOf(p.getBedrooms()),
                String.valueOf(p.getBathrooms()), p.getStatus(), String.valueOf(p.getUpdatedAt()));
    }

    protected String leadSnapshot(UUID id) {
        Lead l = leadRepository.findById(id).orElseThrow();
        return String.join("|", String.valueOf(l.getId()), String.valueOf(l.getTenantId()),
                String.valueOf(l.getPropertyId()), l.getCustomerName(), String.valueOf(l.getEmail()),
                l.getPhone(), String.valueOf(l.getSource()), String.valueOf(l.getMessage()),
                l.getStatus(), String.valueOf(l.getFollowUpAt()), String.valueOf(l.getUpdatedAt()));
    }

    protected String userSnapshot(UUID id) {
        User u = userRepository.findById(id).orElseThrow();
        return String.join("|", String.valueOf(u.getId()), String.valueOf(u.getTenantId()), u.getFullName(),
                u.getEmail(), u.getPassword(), u.getRole(), u.getStatus(), String.valueOf(u.getUpdatedAt()));
    }

    protected String imageSnapshot(UUID id) throws IOException {
        PropertyImage i = imageRepository.findById(id).orElseThrow();
        Path file = uploadPath().resolve(i.getStoredFileName());
        return String.join("|", String.valueOf(i.getId()), String.valueOf(i.getTenantId()),
                i.getOriginalFileName(), i.getStoredFileName(), String.valueOf(i.getFeatured()),
                String.valueOf(i.getFileSize()), "file-exists=" + Files.exists(file),
                "file-size=" + (Files.exists(file) ? Files.size(file) : -1));
    }

    // ---- request bodies ------------------------------------------------------------------------------------

    protected String propertyJson(String title, String status) {
        return "{\"title\":\"" + title + "\",\"description\":\"d\",\"transactionType\":\"SALE\","
                + "\"propertyCategory\":\"RESIDENTIAL\",\"propertyType\":\"APARTMENT\",\"city\":\"Hyderabad\","
                + "\"price\":250000,\"areaSqft\":900,\"bedrooms\":2,\"bathrooms\":2,\"status\":\"" + status + "\"}";
    }

    protected String leadJson(String customerName, UUID propertyId) {
        return "{\"customerName\":\"" + customerName + "\",\"phone\":\"9111111111\","
                + "\"email\":\"lead@customer.example\",\"source\":\"WEBSITE\",\"message\":\"hello\","
                + "\"propertyId\":" + (propertyId == null ? "null" : "\"" + propertyId + "\"") + "}";
    }
}

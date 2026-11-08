
package com.nexzian.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.nexzian.dto.PropertyImageResponse;
import com.nexzian.dto.PublicPropertyImageResponse;
import com.nexzian.entity.Property;
import com.nexzian.entity.PropertyImage;
import com.nexzian.repository.PropertyImageRepository;
import com.nexzian.repository.PropertyRepository;


@Service
@Transactional
public class PropertyImageService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_IMAGES_PER_PROPERTY = 20;

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    private final PropertyImageRepository propertyImageRepository;
    private final PropertyRepository propertyRepository;
    private final Path uploadDirectory;

    public PropertyImageService(
            PropertyImageRepository propertyImageRepository,
            PropertyRepository propertyRepository,
            @Value("${nexzian.file.upload-dir}") String uploadDir) {

        this.propertyImageRepository = propertyImageRepository;
        this.propertyRepository = propertyRepository;
        this.uploadDirectory = Paths.get(uploadDir)
                .toAbsolutePath()
                .normalize();

        try {
            Files.createDirectories(this.uploadDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create property image upload directory",
                    exception);
        }
    }

    public PropertyImageResponse uploadImage(
            UUID propertyId,
            UUID tenantId,
            MultipartFile file) {

        Property property = verifyPropertyOwnership(propertyId, tenantId);

        validateImage(file);

        long imageCount =
                propertyImageRepository.countByPropertyIdAndTenantId(
                        propertyId, tenantId);

        if (imageCount >= MAX_IMAGES_PER_PROPERTY) {
            throw new IllegalArgumentException(
                    "Maximum 20 images are allowed per property");
        }

        String originalFileName = sanitizeFileName(
                file.getOriginalFilename());

        String extension = getExtension(originalFileName);

        String storedFileName = UUID.randomUUID() + extension;

        Path targetPath = uploadDirectory.resolve(storedFileName)
                .normalize();

        if (!targetPath.startsWith(uploadDirectory)) {
            throw new IllegalArgumentException("Invalid image file path");
        }

        try {
            Files.copy(file.getInputStream(), targetPath);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not store property image", exception);
        }

        PropertyImage image = new PropertyImage();

        image.setTenantId(tenantId);
        image.setProperty(property);
        image.setOriginalFileName(originalFileName);
        image.setStoredFileName(storedFileName);
        image.setFilePath(targetPath.toString());
        image.setContentType(file.getContentType());
        image.setFileSize(file.getSize());

        boolean isFirstImage = imageCount == 0;
        image.setFeatured(isFirstImage);

        try {
            PropertyImage savedImage =
                    propertyImageRepository.save(image);

            return toResponse(savedImage);

        } catch (RuntimeException exception) {
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ignored) {
                // Preserve the original database exception.
            }

            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<PropertyImageResponse> getPropertyImages(
            UUID propertyId,
            UUID tenantId) {

        verifyPropertyOwnership(propertyId, tenantId);

        return propertyImageRepository
                .findAllByPropertyIdAndTenantIdOrderByCreatedAtAsc(
                        propertyId, tenantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Resource getImageFile(
            UUID propertyId,
            UUID imageId,
            UUID tenantId) {

        PropertyImage image =
                findImage(propertyId, imageId, tenantId);

        try {
            Path filePath = uploadDirectory
                    .resolve(image.getStoredFileName())
                    .normalize();

            if (!filePath.startsWith(uploadDirectory)) {
                throw new IllegalArgumentException(
                        "Invalid image path");
            }

            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                throw new IllegalArgumentException(
                        "Image file not found");
            }

            return resource;

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not read property image", exception);
        }
    }

    public PropertyImageResponse setFeaturedImage(
            UUID propertyId,
            UUID imageId,
            UUID tenantId) {

        verifyPropertyOwnership(propertyId, tenantId);

        PropertyImage selectedImage =
                findImage(propertyId, imageId, tenantId);

        List<PropertyImage> images =
                propertyImageRepository
                        .findAllByPropertyIdAndTenantIdOrderByCreatedAtAsc(
                                propertyId, tenantId);

        for (PropertyImage image : images) {
            image.setFeatured(false);
        }

        selectedImage.setFeatured(true);

        propertyImageRepository.saveAll(images);

        return toResponse(selectedImage);
    }

    public void deleteImage(
            UUID propertyId,
            UUID imageId,
            UUID tenantId) {

        PropertyImage image =
                findImage(propertyId, imageId, tenantId);

        Path filePath = uploadDirectory
                .resolve(image.getStoredFileName())
                .normalize();

        propertyImageRepository.delete(image);

        try {
            Files.deleteIfExists(filePath);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Image record deleted, but file could not be removed",
                    exception);
        }
    }
    
    @Transactional(readOnly = true)
    public List<PublicPropertyImageResponse> getPublicPropertyImages(
            UUID propertyId,
            UUID tenantId,
            String websiteSlug) {

        // Only published properties can expose images publicly.
        propertyRepository
                .findByIdAndTenantIdAndStatus(
                        propertyId, tenantId, "PUBLISHED")
                .orElseThrow(() -> new IllegalArgumentException(
                        "Published property not found"));

        return propertyImageRepository
                .findAllByPropertyIdAndTenantIdOrderByCreatedAtAsc(
                        propertyId, tenantId)
                .stream()
                .map(image -> {
                    PublicPropertyImageResponse response =
                            new PublicPropertyImageResponse();

                    response.setId(image.getId());
                    response.setOriginalFileName(
                            image.getOriginalFileName());
                    response.setContentType(image.getContentType());
                    response.setFileSize(image.getFileSize());
                    response.setFeatured(image.getFeatured());

                    response.setImageUrl(
                            "/api/v1/public/"
                            + websiteSlug
                            + "/properties/"
                            + propertyId
                            + "/images/"
                            + image.getId()
                            + "/file");

                    return response;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Resource getPublicImageFile(
            UUID propertyId,
            UUID imageId,
            UUID tenantId) {

        // Verify that the property belongs to this agency
        // and is published before exposing its image.
        propertyRepository
                .findByIdAndTenantIdAndStatus(
                        propertyId, tenantId, "PUBLISHED")
                .orElseThrow(() -> new IllegalArgumentException(
                        "Published property not found"));

        return getImageFile(propertyId, imageId, tenantId);
    }

    private PropertyImage findImage(
            UUID propertyId,
            UUID imageId,
            UUID tenantId) {

        return propertyImageRepository
                .findByIdAndPropertyIdAndTenantId(
                        imageId, propertyId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Property image not found"));
    }

    private Property verifyPropertyOwnership(
            UUID propertyId,
            UUID tenantId) {

        return propertyRepository
                .findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Property not found"));
    }

    private void validateImage(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                    "Please select an image file");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "Image size must not exceed 5 MB");
        }

        String contentType = file.getContentType();

        if (contentType == null
                || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {

            throw new IllegalArgumentException(
                    "Only JPG, PNG and WEBP images are allowed");
        }
    }

    private String getExtension(String fileName) {

        int lastDot = fileName.lastIndexOf('.');

        if (lastDot < 0) {
            throw new IllegalArgumentException(
                    "Image file must have a valid extension");
        }

        String extension = fileName.substring(lastDot).toLowerCase();

        if (!Set.of(".jpg", ".jpeg", ".png", ".webp")
                .contains(extension)) {

            throw new IllegalArgumentException(
                    "Unsupported image file extension");
        }

        return extension;
    }

    private String sanitizeFileName(String fileName) {

        if (fileName == null || fileName.isBlank()) {
            return "property-image";
        }

        String sanitized = fileName.replaceAll(
                "[^a-zA-Z0-9._-]", "_");

        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(
                    sanitized.length() - 255);
        }

        return sanitized;
    }

    private PropertyImageResponse toResponse(PropertyImage image) {

        PropertyImageResponse response =
                new PropertyImageResponse();

        response.setId(image.getId());
        response.setPropertyId(image.getProperty().getId());
        response.setOriginalFileName(image.getOriginalFileName());
        response.setContentType(image.getContentType());
        response.setFileSize(image.getFileSize());
        response.setFeatured(image.getFeatured());

        response.setImageUrl(
                "/api/v1/properties/"
                + image.getProperty().getId()
                + "/images/"
                + image.getId()
                + "/file");

        response.setCreatedAt(image.getCreatedAt());

        return response;
    }
}

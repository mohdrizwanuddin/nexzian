
import { useEffect, useMemo, useState, type FormEvent } from "react";
import "./App.css";
import PropertyImageGallery from "./components/PropertyImageGallery";
import PublicAgencySite from "./components/PublicAgencySite";
import PlatformAdminDashboard from "./components/PlatformAdminDashboard";
import OwnerAdminDashboard from "./components/OwnerAdminDashboard";
import {
  ApiError,
  apiRequest,
  onSessionExpired,
} from "./services/api";

interface LoginResponse {
  userId: string;
  tenantId: string;
  fullName: string;
  email: string;
  role: string;
}

interface RegistrationForm {
  businessName: string;
  fullName: string;
  email: string;
  password: string;
  phone: string;
  city: string;
  locality: string;
}

const emptyRegistrationForm: RegistrationForm = {
  businessName: "", fullName: "", email: "", password: "", phone: "", city: "", locality: "",
};

interface Property {
  id: string;
  title: string;
  description: string;
  transactionType: string;
  propertyCategory: string;
  propertyType: string;
  city: string;
  locality?: string;
  status: string;
  price: number;
  areaSqft: number;
  bedrooms?: number;
  bathrooms?: number;
  createdAt?: string;
  updatedAt?: string;
}

interface Lead {
  id: string;
  propertyId?: string | null;
  customerName: string;
  email?: string | null;
  phone: string;
  source?: string | null;
  message?: string | null;
  status: string;
  followUpAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

interface TeamMember {
  id: string;
  fullName: string;
  email: string;
  role: string;
  status: string;
  createdAt?: string;
}

interface TeamMemberForm { fullName: string; email: string; password: string; role: string; }
const emptyTeamMemberForm: TeamMemberForm = { fullName: "", email: "", password: "", role: "AGENT" };

interface LeadForm {
  customerName: string;
  email: string;
  phone: string;
  source: string;
  message: string;
  propertyId: string;
  followUpAt: string;
}

const emptyLeadForm: LeadForm = {
  customerName: "",
  email: "",
  phone: "",
  source: "WEBSITE",
  message: "",
  propertyId: "",
  followUpAt: "",
};

interface PropertyForm {
  title: string;
  description: string;
  transactionType: string;
  propertyCategory: string;
  propertyType: string;
  city: string;
  locality: string;
  price: string;
  areaSqft: string;
  bedrooms: string;
  bathrooms: string;
  status: string;
}

const emptyForm: PropertyForm = {
  title: "",
  description: "",
  transactionType: "SALE",
  propertyCategory: "RESIDENTIAL",
  propertyType: "APARTMENT",
  city: "Hyderabad",
  locality: "",
  price: "",
  areaSqft: "",
  bedrooms: "2",
  bathrooms: "2",
  status: "AVAILABLE",
};

function WorkspaceApp() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [isRegistering, setIsRegistering] = useState(false);
  const [registrationForm, setRegistrationForm] = useState<RegistrationForm>(emptyRegistrationForm);
  const [registrationLoading, setRegistrationLoading] = useState(false);
  const [registrationMessage, setRegistrationMessage] = useState("");

  const [user, setUser] = useState<LoginResponse | null>(() => {
    if (localStorage.getItem("nexzianSessionMode") !== "cookie-v1") {
      localStorage.removeItem("nexzianUser");
      return null;
    }

    const savedUser = localStorage.getItem("nexzianUser");
    try {
      return savedUser ? JSON.parse(savedUser) : null;
    } catch {
      localStorage.removeItem("nexzianUser");
      localStorage.removeItem("nexzianSessionMode");
      return null;
    }
  });

  const [activePage, setActivePage] = useState<"dashboard" | "properties" | "leads" | "team" | "admin">(
    "dashboard"
  );
  const [properties, setProperties] = useState<Property[]>([]);
  const [propertiesLoading, setPropertiesLoading] = useState(false);
  const [propertyError, setPropertyError] = useState("");
  const [successMessage, setSuccessMessage] = useState("");
  const [searchTerm, setSearchTerm] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [showPropertyForm, setShowPropertyForm] = useState(false);
  const [editingPropertyId, setEditingPropertyId] = useState<string | null>(
    null
  );
  const [form, setForm] = useState<PropertyForm>(emptyForm);
  const [savingProperty, setSavingProperty] = useState(false);
  const [galleryProperty, setGalleryProperty] = useState<Property | null>(null);

  const [leads, setLeads] = useState<Lead[]>([]);
  const [leadsLoading, setLeadsLoading] = useState(false);
  const [leadError, setLeadError] = useState("");
  const [leadSuccess, setLeadSuccess] = useState("");
  const [leadSearch, setLeadSearch] = useState("");
  const [leadStatusFilter, setLeadStatusFilter] = useState("ALL");
  const [showLeadForm, setShowLeadForm] = useState(false);
  const [editingLeadId, setEditingLeadId] = useState<string | null>(null);
  const [leadForm, setLeadForm] = useState<LeadForm>(emptyLeadForm);
  const [savingLead, setSavingLead] = useState(false);
  const [detailProperty, setDetailProperty] = useState<Property | null>(null);
  const [detailLead, setDetailLead] = useState<Lead | null>(null);
  const [teamMembers, setTeamMembers] = useState<TeamMember[]>([]);
  const [teamLoading, setTeamLoading] = useState(false);
  const [teamError, setTeamError] = useState("");
  const [showTeamForm, setShowTeamForm] = useState(false);
  const [teamForm, setTeamForm] = useState<TeamMemberForm>(emptyTeamMemberForm);
  const [savingTeamMember, setSavingTeamMember] = useState(false);

  const handleLogin = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");

    if (!email.trim() || !password) {
      setError("Please enter your email and password.");
      return;
    }

    setLoading(true);

    try {
      const data = await apiRequest<LoginResponse>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ email: email.trim(), password }),
      });

      localStorage.setItem("nexzianUser", JSON.stringify(data));
      localStorage.setItem("nexzianSessionMode", "cookie-v1");
      setUser(data);
      setPassword("");
      setActivePage("dashboard");
    } catch (err) {
      setError(
        err instanceof ApiError && err.isUnauthorized
          ? "Invalid email or password."
          : err instanceof ApiError && err.status === 400
            ? "Please check your email and password."
          : err instanceof TypeError
          ? "Unable to connect to the server. Please check that the backend is running."
          : err instanceof Error
            ? err.message
            : "Something went wrong."
      );
    } finally {
      setLoading(false);
    }
  };

  const handleRegistrationChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = event.target;
    setRegistrationForm((previous) => ({ ...previous, [name]: value }));
  };

  const handleRegistration = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    setRegistrationMessage("");
    setRegistrationLoading(true);

    try {
      // One atomic call: the server creates the agency and its OWNER account together.
      await apiRequest("/auth/register-agency", {
        method: "POST",
        body: JSON.stringify({
          businessName: registrationForm.businessName.trim(),
          fullName: registrationForm.fullName.trim(),
          email: registrationForm.email.trim(),
          password: registrationForm.password,
          phone: registrationForm.phone.trim(),
          city: registrationForm.city.trim(),
          locality: registrationForm.locality.trim(),
        }),
      });
      setEmail(registrationForm.email.trim());
      setPassword("");
      setRegistrationForm(emptyRegistrationForm);
      setIsRegistering(false);
      setRegistrationMessage("Agency registered successfully. Sign in with your new account.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to register your agency.");
    } finally {
      setRegistrationLoading(false);
    }
  };

  const handleLogout = () => {
    void apiRequest<void>("/auth/logout", { method: "POST" }).catch(() => undefined);
    localStorage.removeItem("nexzianUser");
    localStorage.removeItem("nexzianSessionMode");
    setUser(null);
    setEmail("");
    setPassword("");
    setError("");
    setProperties([]);
    setLeads([]);
    setTeamMembers([]);
  };

  useEffect(() => onSessionExpired(() => {
    localStorage.removeItem("nexzianUser");
    localStorage.removeItem("nexzianSessionMode");
    setUser(null);
    setProperties([]);
    setLeads([]);
    setTeamMembers([]);
    setError("Your session has expired. Please sign in again.");
  }), []);

  const loadProperties = async () => {
    if (!user) return;

    setPropertiesLoading(true);
    setPropertyError("");

    try {
      const data = await apiRequest<Property[]>("/properties");
      setProperties(Array.isArray(data) ? data : []);
    } catch (err) {
      setPropertyError(
        err instanceof TypeError
          ? "Unable to connect to the backend. Check that Spring Boot is running."
          : err instanceof Error
            ? err.message
            : "Unable to load properties."
      );
    } finally {
      setPropertiesLoading(false);
    }
  };

  useEffect(() => {
    if (!user || user.role !== "OWNER") {
      return;
    }

    const timerId = window.setTimeout(() => {
      void loadProperties();
    }, 0);

    return () => {
      window.clearTimeout(timerId);
    };

    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  const loadLeads = async () => {
    if (!user) return;
    setLeadsLoading(true);
    setLeadError("");
    try {
      const data = await apiRequest<Lead[]>("/leads");
      setLeads(Array.isArray(data) ? data : data ? [data] : []);
    } catch (err) {
      setLeadError(
        err instanceof Error ? err.message : "Unable to load leads."
      );
    } finally {
      setLeadsLoading(false);
    }
  };

  const loadTeam = async () => {
    if (!user || user.role !== "OWNER") return;

    setTeamLoading(true);
    setTeamError("");
    try {
      const data = await apiRequest<TeamMember[]>("/users");
      setTeamMembers(Array.isArray(data) ? data : []);
    } catch (err) {
      setTeamError(err instanceof Error ? err.message : "Unable to load team members.");
    } finally {
      setTeamLoading(false);
    }
  };

  const saveTeamMember = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setSavingTeamMember(true); setTeamError("");
    try { await apiRequest("/users/team", { method: "POST", body: JSON.stringify(teamForm) }); setShowTeamForm(false); setTeamForm(emptyTeamMemberForm); await loadTeam(); }
    catch (err) { setTeamError(err instanceof Error ? err.message : "Unable to add employee."); }
    finally { setSavingTeamMember(false); }
  };

  const updateTeamMemberAccess = async (member: TeamMember, status: string) => {
    try { await apiRequest(`/users/${member.id}/access`, { method: "PUT", body: JSON.stringify({ role: member.role, status }) }); await loadTeam(); }
    catch (err) { setTeamError(err instanceof Error ? err.message : "Unable to update employee access."); }
  };

  useEffect(() => {
    if (!user || user.role !== "OWNER") return;
    const timerId = window.setTimeout(() => void loadLeads(), 0);
    return () => window.clearTimeout(timerId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  useEffect(() => {
    if (!user || user.role !== "OWNER") return;
    const timerId = window.setTimeout(() => void loadTeam(), 0);
    return () => window.clearTimeout(timerId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  const openAddLeadForm = () => {
    setEditingLeadId(null);
    setLeadForm(emptyLeadForm);
    setLeadError("");
    setLeadSuccess("");
    setShowLeadForm(true);
  };

  const openEditLeadForm = (lead: Lead) => {
    setEditingLeadId(lead.id);
    setLeadForm({
      customerName: lead.customerName,
      email: lead.email || "",
      phone: lead.phone,
      source: lead.source || "WEBSITE",
      message: lead.message || "",
      propertyId: lead.propertyId || "",
      followUpAt: lead.followUpAt ? lead.followUpAt.slice(0, 16) : "",
    });
    setLeadError("");
    setLeadSuccess("");
    setShowLeadForm(true);
  };

  const handleLeadFormChange = (
    event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => {
    const { name, value } = event.target;
    setLeadForm((previous) => ({ ...previous, [name]: value }));
  };

  const saveLead = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSavingLead(true);
    setLeadError("");
    setLeadSuccess("");
    const payload = {
      customerName: leadForm.customerName.trim(),
      email: leadForm.email.trim() || null,
      phone: leadForm.phone.trim(),
      source: leadForm.source,
      message: leadForm.message.trim(),
      propertyId: leadForm.propertyId || null,
      status: editingLeadId
        ? leads.find((lead) => lead.id === editingLeadId)?.status || "NEW"
        : "NEW",
      followUpAt: leadForm.followUpAt
        ? `${leadForm.followUpAt}:00`
        : null,
    };
    try {
      await apiRequest(
        editingLeadId ? `/leads/${editingLeadId}` : "/leads",
        {
          method: editingLeadId ? "PUT" : "POST",
          body: JSON.stringify(payload),
        }
      );
      setShowLeadForm(false);
      setLeadForm(emptyLeadForm);
      setEditingLeadId(null);
      setLeadSuccess(editingLeadId ? "Lead updated successfully." : "Lead created successfully.");
      await loadLeads();
    } catch (err) {
      setLeadError(err instanceof Error ? err.message : "Unable to save lead.");
    } finally {
      setSavingLead(false);
    }
  };

  const updateLeadStatus = async (lead: Lead, status: string) => {
    setLeadError("");
    setLeadSuccess("");
    try {
      await apiRequest(`/leads/${lead.id}`, {
        method: "PUT",
        body: JSON.stringify({
          customerName: lead.customerName,
          email: lead.email || null,
          phone: lead.phone,
          source: lead.source || "",
          message: lead.message || "",
          propertyId: lead.propertyId || null,
          status,
          followUpAt: lead.followUpAt || null,
        }),
      });
      setLeadSuccess(`Lead status updated to ${status.replaceAll("_", " ")}.`);
      await loadLeads();
    } catch (err) {
      setLeadError(err instanceof Error ? err.message : "Unable to update lead status.");
    }
  };

  const deleteLead = async (lead: Lead) => {
    if (!window.confirm(`Delete lead for ${lead.customerName}?`)) return;
    setLeadError("");
    setLeadSuccess("");
    try {
      await apiRequest(`/leads/${lead.id}`, {
        method: "DELETE",
      });
      setLeadSuccess("Lead deleted successfully.");
      await loadLeads();
    } catch (err) {
      setLeadError(err instanceof Error ? err.message : "Unable to delete lead.");
    }
  };

  const filteredLeads = useMemo(() => {
    const search = leadSearch.trim().toLowerCase();
    return leads.filter((lead) => {
      const matchesSearch = !search ||
        lead.customerName.toLowerCase().includes(search) ||
        lead.phone.toLowerCase().includes(search) ||
        (lead.email || "").toLowerCase().includes(search);
      const matchesStatus = leadStatusFilter === "ALL" || lead.status === leadStatusFilter;
      return matchesSearch && matchesStatus;
    });
  }, [leads, leadSearch, leadStatusFilter]);

  const filteredProperties = useMemo(() => {
    const search = searchTerm.trim().toLowerCase();

    return properties.filter((property) => {
      const matchesSearch =
        !search ||
        property.title.toLowerCase().includes(search) ||
        property.city.toLowerCase().includes(search) ||
        (property.locality || "").toLowerCase().includes(search);

      const matchesStatus =
        statusFilter === "ALL" || property.status === statusFilter;

      return matchesSearch && matchesStatus;
    });
  }, [properties, searchTerm, statusFilter]);

  const formatPrice = (price: number) =>
    new Intl.NumberFormat("en-IN", {
      style: "currency",
      currency: "INR",
      maximumFractionDigits: 0,
    }).format(price);

  const openAddForm = () => {
    setEditingPropertyId(null);
    setForm(emptyForm);
    setPropertyError("");
    setSuccessMessage("");
    setShowPropertyForm(true);
  };

  const openEditForm = (property: Property) => {
    setEditingPropertyId(property.id);
    setForm({
      title: property.title,
      description: property.description || "",
      transactionType: property.transactionType,
      propertyCategory: property.propertyCategory,
      propertyType: property.propertyType,
      city: property.city,
      locality: property.locality || "",
      price: String(property.price),
      areaSqft: String(property.areaSqft),
      bedrooms: String(property.bedrooms ?? 0),
      bathrooms: String(property.bathrooms ?? 0),
      status: property.status,
    });
    setPropertyError("");
    setSuccessMessage("");
    setShowPropertyForm(true);
  };

  const handleFormChange = (
    event: React.ChangeEvent<
      HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
    >
  ) => {
    const { name, value } = event.target;
    setForm((previous) => ({ ...previous, [name]: value }));
  };

  const saveProperty = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSavingProperty(true);
    setPropertyError("");
    setSuccessMessage("");

    const payload = {
      title: form.title.trim(),
      description: form.description.trim(),
      transactionType: form.transactionType,
      propertyCategory: form.propertyCategory,
      propertyType: form.propertyType,
      city: form.city.trim(),
      locality: form.locality.trim(),
      price: Number(form.price),
      areaSqft: Number(form.areaSqft),
      bedrooms: Number(form.bedrooms),
      bathrooms: Number(form.bathrooms),
      status: form.status,
    };

    try {
      const isEditing = editingPropertyId !== null;
      const url = isEditing ? `/properties/${editingPropertyId}` : "/properties";

      await apiRequest(url, {
        method: isEditing ? "PUT" : "POST",
        body: JSON.stringify(payload),
      });

      setShowPropertyForm(false);
      setForm(emptyForm);
      setEditingPropertyId(null);
      setSuccessMessage(
        isEditing
          ? "Property updated successfully."
          : "Property added successfully."
      );
      await loadProperties();
    } catch (err) {
      setPropertyError(
        err instanceof Error ? err.message : "Unable to save property."
      );
    } finally {
      setSavingProperty(false);
    }
  };

  const deleteProperty = async (property: Property) => {
    const confirmed = window.confirm(
      `Are you sure you want to delete "${property.title}"?`
    );

    if (!confirmed) return;

    setPropertyError("");
    setSuccessMessage("");

    try {
      await apiRequest(`/properties/${property.id}`, { method: "DELETE" });

      setSuccessMessage("Property deleted successfully.");
      await loadProperties();
    } catch (err) {
      setPropertyError(
        err instanceof Error ? err.message : "Unable to delete property."
      );
    }
  };

  if (user) {
    if (user.role === "PLATFORM_ADMIN") {
      return <PlatformAdminDashboard onLogout={handleLogout} />;
    }
    const isOwner = user.role === "OWNER";
    const availableCount = properties.filter(
      (property) => property.status === "AVAILABLE"
    ).length;

    const draftCount = properties.filter(
      (property) => property.status === "DRAFT"
    ).length;

    return (
      <div className="workspace">
        <aside className="sidebar">
          <div className="sidebar-brand">
            <span className="brand-symbol">N</span>
            <span className="brand-name">NEXZIAN</span>
          </div>

          <div className="sidebar-section-label">WORKSPACE</div>

          <button
            className={`nav-item ${
              activePage === "dashboard" ? "active" : ""
            }`}
            onClick={() => {
              setActivePage("dashboard");
              setPropertyError("");
              setSuccessMessage("");
            }}
          >
            <span className="nav-icon">⌂</span>
            Dashboard
          </button>

          <button
            className={`nav-item ${
              activePage === "properties" ? "active" : ""
            }`}
            onClick={() => {
              setActivePage("properties");
              setPropertyError("");
              setSuccessMessage("");
            }}
          >
            <span className="nav-icon">▦</span>
            Properties
            <span className="nav-count">{properties.length}</span>
          </button>

          <button
            className={`nav-item ${
              activePage === "leads" ? "active" : ""
            }`}
            onClick={() => {
              setActivePage("leads");
              setLeadError("");
              setLeadSuccess("");
              void loadLeads();
            }}
          >
            <span className="nav-icon">♧</span>
            Leads & CRM
            <span className="nav-count">{leads.length}</span>
          </button>

          {user.role === "OWNER" && (
            <button
              className={`nav-item ${activePage === "admin" ? "active" : ""}`}
              onClick={() => setActivePage("admin")}
            ><span className="nav-icon">◈</span>Agency Admin</button>
          )}

          {user.role === "OWNER" && (
            <button
              className={`nav-item ${activePage === "team" ? "active" : ""}`}
              onClick={() => {
                setActivePage("team");
                setTeamError("");
                void loadTeam();
              }}
            >
              <span className="nav-icon">♙</span>
              Team
              <span className="nav-count">{teamMembers.length}</span>
            </button>
          )}

          <div className="sidebar-bottom">
            <div className="sidebar-user">
              <div className="user-avatar">
                {user.fullName.charAt(0).toUpperCase()}
              </div>
              <div className="sidebar-user-info">
                <strong>{user.fullName}</strong>
                <span>{user.role}</span>
              </div>
            </div>
            <button className="sidebar-logout" onClick={handleLogout}>
              Sign Out
            </button>
          </div>
        </aside>

        <main className="main-area">
          <header className="topbar">
            <div>
              <span className="topbar-label">NEXZIAN WORKSPACE</span>
              <strong>
                {activePage === "dashboard" ? "Dashboard" : activePage === "properties" ? "Property Management" : activePage === "leads" ? "Lead Management" : activePage === "team" ? "Team Management" : "Agency Administration"}
              </strong>
            </div>
            <div className="topbar-account">
              <span className="account-dot" />
              {user.fullName}
            </div>
          </header>

          <div className="page-content">
            {activePage === "admin" ? <OwnerAdminDashboard /> : activePage === "dashboard" ? (
              <>
                <div className="page-heading">
                  <div>
                    <p className="eyebrow">BUSINESS OVERVIEW</p>
                    <h1>Welcome, {user.fullName}</h1>
                    <p className="page-description">
                      Here's what's happening with your real estate business.
                    </p>
                  </div>
                  {isOwner && <button
                    className="primary-button"
                    onClick={() => {
                      setActivePage("properties");
                      openAddForm();
                    }}
                  >
                    + Add Property
                  </button>}
                </div>

                <div className="stat-grid">
                  <div className="stat-card">
                    <span className="stat-label">TOTAL PROPERTIES</span>
                    <strong>{properties.length}</strong>
                    <span className="stat-footnote">Your property portfolio</span>
                  </div>
                  <div className="stat-card">
                    <span className="stat-label">AVAILABLE</span>
                    <strong>{availableCount}</strong>
                    <span className="stat-footnote">Ready for enquiries</span>
                  </div>
                  <div className="stat-card">
                    <span className="stat-label">DRAFT LISTINGS</span>
                    <strong>{draftCount}</strong>
                    <span className="stat-footnote">Not yet published</span>
                  </div>
                </div>

                <section className="panel">
                  <div className="panel-heading">
                    <div>
                      <h2>Your workspace</h2>
                      <p>Manage your property portfolio from one place.</p>
                    </div>
                    <button
                      className="text-button"
                      onClick={() => setActivePage("properties")}
                    >
                      View properties →
                    </button>
                  </div>

				  <div className="workspace-welcome">
				    <div className="welcome-mark">N</div>
				    <div>
				      <h3>
				        {properties.length > 0
				          ? "Your property portfolio is growing."
				          : "Your real estate business starts here."}
				      </h3>
				      <p>
				        {properties.length > 0
				          ? `You currently manage ${properties.length} ${
				              properties.length === 1 ? "property" : "properties"
				            } with Nexzian. Keep your portfolio updated.`
				          : "Add your first property listing and start building your digital portfolio with Nexzian."}
				      </p>
				    </div>
				  </div>
                </section>

                <section className="account-details">
                  <h2>Account information</h2>
                  <div className="detail-row">
                    <span>Account name</span>
                    <strong>{user.fullName}</strong>
                  </div>
                  <div className="detail-row">
                    <span>Email address</span>
                    <strong>{user.email}</strong>
                  </div>
                  <div className="detail-row">
                    <span>Account role</span>
                    <strong>{user.role}</strong>
                  </div>
                  <div className="detail-row">
                    <span>Tenant ID</span>
                    <strong>{user.tenantId}</strong>
                  </div>
                </section>
              </>
            ) : activePage === "properties" ? (
              <>
                <div className="page-heading">
                  <div>
                    <p className="eyebrow">PROPERTY PORTFOLIO</p>
                    <h1>Properties</h1>
                    <p className="page-description">
                      Manage, organize and update your property listings.
                    </p>
                  </div>
                  {isOwner && <button className="primary-button" onClick={openAddForm}>
                    + Add Property
                  </button>}
                </div>

                <div className="property-toolbar">
                  <input
                    className="search-input"
                    type="search"
                    placeholder="Search by title, city or locality..."
                    value={searchTerm}
                    onChange={(event) => setSearchTerm(event.target.value)}
                  />
                  <select
                    className="filter-select"
                    value={statusFilter}
                    onChange={(event) => setStatusFilter(event.target.value)}
                  >
                    <option value="ALL">All statuses</option>
                    <option value="AVAILABLE">Available</option>
                    <option value="DRAFT">Draft</option>
                    <option value="SOLD">Sold</option>
                    <option value="RENTED">Rented</option>
                  </select>
                  <button
                    className="secondary-button"
                    onClick={() => void loadProperties()}
                    disabled={propertiesLoading}
                  >
                    ↻ Refresh
                  </button>
                </div>

                {successMessage && (
                  <div className="success-message" role="status">
                    {successMessage}
                  </div>
                )}

                {propertyError && (
                  <div className="error-message property-alert" role="alert">
                    {propertyError}
                  </div>
                )}

                <section className="property-panel">
                  <div className="property-panel-heading">
                    <div>
                      <h2>All properties</h2>
                      <p>
                        {filteredProperties.length} listing
                        {filteredProperties.length === 1 ? "" : "s"} found
                      </p>
                    </div>
                  </div>

                  {propertiesLoading ? (
                    <div className="empty-state">
                      <div className="loading-spinner" />
                      <p>Loading your properties...</p>
                    </div>
                  ) : filteredProperties.length === 0 ? (
                    <div className="empty-state">
                      <div className="empty-icon">⌂</div>
                      <h3>
                        {properties.length === 0
                          ? "No properties added yet"
                          : "No matching properties"}
                      </h3>
                      <p>
                        {properties.length === 0
                          ? "Start building your portfolio by adding your first property."
                          : "Try changing your search or status filter."}
                      </p>
                      {isOwner && properties.length === 0 && (
                        <button className="primary-button" onClick={openAddForm}>
                          + Add your first property
                        </button>
                      )}
                    </div>
                  ) : (
                    <div className="table-scroll">
                      <table className="property-table">
                        <thead>
                          <tr>
                            <th>PROPERTY</th>
                            <th>TYPE</th>
                            <th>LOCATION</th>
                            <th>PRICE</th>
                            <th>STATUS</th>
                            <th>ACTIONS</th>
                          </tr>
                        </thead>
                        <tbody>
                          {filteredProperties.map((property) => (
                            <tr key={property.id}>
                              <td>
                                <strong className="property-title">
                                  {property.title}
                                </strong>
                                <span className="property-subtitle">
                                  {property.areaSqft.toLocaleString("en-IN")} sqft
                                  {property.bedrooms
                                    ? ` · ${property.bedrooms} BHK`
                                    : ""}
                                </span>
                              </td>
                              <td>
                                <span className="type-label">
                                  {property.transactionType}
                                </span>
                                <span className="property-subtitle">
                                  {property.propertyType}
                                </span>
                              </td>
                              <td>
                                <strong>{property.city}</strong>
                                <span className="property-subtitle">
                                  {property.locality || "—"}
                                </span>
                              </td>
                              <td className="price-cell">
                                {formatPrice(property.price)}
                              </td>
                              <td>
                                <span
                                  className={`status-pill ${property.status.toLowerCase()}`}
                                >
                                  {property.status}
                                </span>
                              </td>
                              <td>
                                {isOwner && <div className="table-actions">
                                  <button
                                    className="action-button"
                                    onClick={() => setDetailProperty(property)}
                                  >
                                    View details
                                  </button>
                                  <button
                                    className="action-button"
                                    onClick={() => openEditForm(property)}
                                  >
                                    Edit
                                  </button>
                                  <button
                                    className="action-button"
                                    onClick={() => setGalleryProperty(property)}
                                  >
                                    Manage Photos
                                  </button>
                                  <button
                                    className="action-button delete-action"
                                    onClick={() => void deleteProperty(property)}
                                  >
                                    Delete
                                  </button>
                                </div>}
                                {!isOwner && <button className="action-button" onClick={() => setDetailProperty(property)}>View details</button>}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </section>
              </>
            ) : activePage === "leads" ? (
              <>
                <div className="page-heading">
                  <div>
                    <p className="eyebrow">CUSTOMER RELATIONSHIP MANAGEMENT</p>
                    <h1>Leads</h1>
                    <p className="page-description">Capture enquiries, manage conversations and stay on top of follow-ups.</p>
                  </div>
                  {isOwner && <button className="primary-button" onClick={openAddLeadForm}>+ Add Lead</button>}
                </div>

                <div className="stat-grid">
                  <div className="stat-card"><span className="stat-label">TOTAL LEADS</span><strong>{leads.length}</strong><span className="stat-footnote">All customer enquiries</span></div>
                  <div className="stat-card"><span className="stat-label">NEW</span><strong>{leads.filter((lead) => lead.status === "NEW").length}</strong><span className="stat-footnote">Awaiting first contact</span></div>
                  <div className="stat-card"><span className="stat-label">IN PROGRESS</span><strong>{leads.filter((lead) => ["CONTACTED", "VISIT_SCHEDULED", "NEGOTIATION"].includes(lead.status)).length}</strong><span className="stat-footnote">Being followed up</span></div>
                  <div className="stat-card"><span className="stat-label">CONVERTED</span><strong>{leads.filter((lead) => lead.status === "CONVERTED").length}</strong><span className="stat-footnote">Successful outcomes</span></div>
                </div>

                <div className="property-toolbar">
                  <input className="search-input" type="search" placeholder="Search by name, phone or email..." value={leadSearch} onChange={(event) => setLeadSearch(event.target.value)} />
                  <select className="filter-select" value={leadStatusFilter} onChange={(event) => setLeadStatusFilter(event.target.value)}>
                    <option value="ALL">All statuses</option>
                    <option value="NEW">New</option>
                    <option value="CONTACTED">Contacted</option>
                    <option value="VISIT_SCHEDULED">Visit scheduled</option>
                    <option value="NEGOTIATION">Negotiation</option>
                    <option value="CONVERTED">Converted</option>
                    <option value="LOST">Lost</option>
                  </select>
                  <button className="secondary-button" onClick={() => void loadLeads()} disabled={leadsLoading}>↻ Refresh</button>
                </div>

                {leadSuccess && <div className="success-message" role="status">{leadSuccess}</div>}
                {leadError && <div className="error-message property-alert" role="alert">{leadError}</div>}

                <section className="property-panel">
                  <div className="property-panel-heading"><div><h2>All leads</h2><p>{filteredLeads.length} {filteredLeads.length === 1 ? "enquiry" : "enquiries"} found</p></div></div>
                  {leadsLoading ? (
                    <div className="empty-state"><div className="loading-spinner" /><p>Loading your leads...</p></div>
                  ) : filteredLeads.length === 0 ? (
                    <div className="empty-state"><div className="empty-icon">♧</div><h3>{leads.length === 0 ? "No leads yet" : "No matching leads"}</h3><p>{leads.length === 0 ? "Add your first customer enquiry to start managing your sales pipeline." : "Try changing your search or status filter."}</p>{isOwner && leads.length === 0 && <button className="primary-button" onClick={openAddLeadForm}>+ Add your first lead</button>}</div>
                  ) : (
                    <div className="table-scroll">
                      <table className="property-table">
                        <thead><tr><th>CUSTOMER</th><th>CONTACT</th><th>SOURCE</th><th>FOLLOW-UP</th><th>STATUS</th><th>ACTIONS</th></tr></thead>
                        <tbody>{filteredLeads.map((lead) => (
                          <tr key={lead.id}>
                            <td><strong className="property-title">{lead.customerName}</strong><span className="property-subtitle">{lead.message || "No message provided"}</span></td>
                            <td><strong>{lead.phone}</strong><span className="property-subtitle">{lead.email || "—"}</span></td>
                            <td><span className="type-label">{lead.source || "—"}</span></td>
                            <td>{lead.followUpAt ? new Date(lead.followUpAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" }) : "—"}</td>
                            <td>{isOwner ? <select className="filter-select" value={lead.status} onChange={(event) => void updateLeadStatus(lead, event.target.value)} aria-label={`Status for ${lead.customerName}`}><option value="NEW">New</option><option value="CONTACTED">Contacted</option><option value="VISIT_SCHEDULED">Visit scheduled</option><option value="NEGOTIATION">Negotiation</option><option value="CONVERTED">Converted</option><option value="LOST">Lost</option></select> : <span className={`status-pill ${lead.status.toLowerCase()}`}>{lead.status}</span>}</td>
                            <td>{isOwner && <div className="table-actions"><button className="action-button" onClick={() => setDetailLead(lead)}>View details</button><button className="action-button" onClick={() => openEditLeadForm(lead)}>Edit</button><button className="action-button delete-action" onClick={() => void deleteLead(lead)}>Delete</button></div>}{!isOwner && <button className="action-button" onClick={() => setDetailLead(lead)}>View details</button>}</td>
                          </tr>
                        ))}</tbody>
                      </table>
                    </div>
                  )}
                </section>
              </>
            ) : (
              <>
                <div className="page-heading">
                  <div>
                    <p className="eyebrow">AGENCY ACCESS</p>
                    <h1>Team</h1>
                    <p className="page-description">View the members who can access your Nexzian workspace.</p>
                  </div>
                  <div className="table-actions"><button className="secondary-button" onClick={() => void loadTeam()} disabled={teamLoading}>↻ Refresh</button><button className="primary-button" onClick={() => { setTeamForm(emptyTeamMemberForm); setShowTeamForm(true); }}>+ Add employee</button></div>
                </div>

                {teamError && <div className="error-message property-alert" role="alert">{teamError}</div>}

                <section className="property-panel">
                  <div className="property-panel-heading">
                    <div><h2>Team members</h2><p>{teamMembers.length} {teamMembers.length === 1 ? "member" : "members"} with workspace access</p></div>
                  </div>
                  {teamLoading ? (
                    <div className="empty-state"><div className="loading-spinner" /><p>Loading your team...</p></div>
                  ) : teamMembers.length === 0 ? (
                    <div className="empty-state"><div className="empty-icon">♙</div><h3>No team members found</h3><p>Your agency currently has no active workspace members.</p></div>
                  ) : (
                    <div className="table-scroll"><table className="property-table team-table">
                      <thead><tr><th>MEMBER</th><th>ROLE</th><th>STATUS</th><th>JOINED</th><th>ACTIONS</th></tr></thead>
                      <tbody>{teamMembers.map((member) => (
                        <tr key={member.id}>
                          <td><strong className="property-title">{member.fullName}</strong><span className="property-subtitle">{member.email}</span></td>
                          <td><span className="type-label">{member.role}</span></td>
                          <td><span className={`status-pill ${member.status.toLowerCase()}`}>{member.status}</span></td>
                          <td>{member.createdAt ? new Date(member.createdAt).toLocaleDateString("en-IN", { dateStyle: "medium" }) : "—"}</td><td>{member.role !== "OWNER" && <button className="action-button delete-action" onClick={() => void updateTeamMemberAccess(member, member.status === "ACTIVE" ? "INACTIVE" : "ACTIVE")}>{member.status === "ACTIVE" ? "Deactivate" : "Activate"}</button>}</td>
                        </tr>
                      ))}</tbody>
                    </table></div>
                  )}
                  <p className="team-note">Member invitations and role changes will appear here when the corresponding secured API endpoints are added.</p>
                </section>
              </>
            )}
          </div>
        </main>

        {showLeadForm && (
          <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setShowLeadForm(false); }}>
            <section className="property-modal" role="dialog" aria-modal="true" aria-labelledby="lead-modal-title">
              <div className="modal-heading"><div><p className="eyebrow">CUSTOMER ENQUIRY</p><h2 id="lead-modal-title">{editingLeadId ? "Edit lead" : "Add new lead"}</h2></div><button className="modal-close" onClick={() => setShowLeadForm(false)} aria-label="Close">×</button></div>
              {leadError && <div className="error-message">{leadError}</div>}
              <form className="property-form" onSubmit={saveLead}>
                <div className="form-group"><label htmlFor="leadCustomerName">Customer name *</label><input id="leadCustomerName" name="customerName" value={leadForm.customerName} onChange={handleLeadFormChange} maxLength={150} required /></div>
                <div className="form-grid">
                  <div className="form-group"><label htmlFor="leadEmail">Email</label><input id="leadEmail" name="email" type="email" value={leadForm.email} onChange={handleLeadFormChange} maxLength={150} /></div>
                  <div className="form-group"><label htmlFor="leadPhone">Phone *</label><input id="leadPhone" name="phone" value={leadForm.phone} onChange={handleLeadFormChange} maxLength={20} required /></div>
                  <div className="form-group"><label htmlFor="leadSource">Source</label><select id="leadSource" name="source" value={leadForm.source} onChange={handleLeadFormChange}><option value="WEBSITE">Website</option><option value="PHONE">Phone</option><option value="WHATSAPP">WhatsApp</option><option value="INSTAGRAM">Instagram</option><option value="FACEBOOK">Facebook</option><option value="REFERRAL">Referral</option><option value="WALK_IN">Walk-in</option><option value="OTHER">Other</option></select></div>
                  <div className="form-group"><label htmlFor="leadPropertyId">Interested property</label><select id="leadPropertyId" name="propertyId" value={leadForm.propertyId} onChange={handleLeadFormChange}><option value="">Not specified</option>{properties.map((property) => <option key={property.id} value={property.id}>{property.title}</option>)}</select></div>
                </div>
                <div className="form-group"><label htmlFor="leadFollowUpAt">Follow-up date and time</label><input id="leadFollowUpAt" name="followUpAt" type="datetime-local" value={leadForm.followUpAt} onChange={handleLeadFormChange} /></div>
                <div className="form-group"><label htmlFor="leadMessage">Enquiry / message</label><textarea id="leadMessage" name="message" value={leadForm.message} onChange={handleLeadFormChange} maxLength={2000} rows={4} /></div>
                <div className="modal-actions"><button type="button" className="secondary-button" onClick={() => setShowLeadForm(false)}>Cancel</button><button type="submit" className="primary-button" disabled={savingLead}>{savingLead ? "Saving..." : editingLeadId ? "Save changes" : "Create lead"}</button></div>
              </form>
            </section>
          </div>
        )}

        {showTeamForm && <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setShowTeamForm(false); }}><section className="property-modal" role="dialog" aria-modal="true" aria-labelledby="team-member-title"><div className="modal-heading"><div><p className="eyebrow">TEAM ACCESS</p><h2 id="team-member-title">Add employee</h2></div><button className="modal-close" onClick={() => setShowTeamForm(false)} aria-label="Close">×</button></div><form className="property-form" onSubmit={saveTeamMember}><div className="form-group"><label htmlFor="teamFullName">Full name *</label><input id="teamFullName" value={teamForm.fullName} onChange={(e) => setTeamForm({ ...teamForm, fullName: e.target.value })} required /></div><div className="form-group"><label htmlFor="teamEmail">Email *</label><input id="teamEmail" type="email" value={teamForm.email} onChange={(e) => setTeamForm({ ...teamForm, email: e.target.value })} required /></div><div className="form-grid"><div className="form-group"><label htmlFor="teamPassword">Temporary password *</label><input id="teamPassword" type="password" minLength={8} value={teamForm.password} onChange={(e) => setTeamForm({ ...teamForm, password: e.target.value })} required /></div><div className="form-group"><label htmlFor="teamRole">Role *</label><select id="teamRole" value={teamForm.role} onChange={(e) => setTeamForm({ ...teamForm, role: e.target.value })}><option value="AGENT">Agent</option><option value="STAFF">Staff</option></select></div></div><p className="page-description">Share the temporary password securely with the employee. They can sign in under this agency.</p><div className="modal-actions"><button type="button" className="secondary-button" onClick={() => setShowTeamForm(false)}>Cancel</button><button className="primary-button" type="submit" disabled={savingTeamMember}>{savingTeamMember ? "Adding..." : "Add employee"}</button></div></form></section></div>}

        {detailProperty && (
          <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setDetailProperty(null); }}>
            <section className="property-modal detail-modal" role="dialog" aria-modal="true" aria-labelledby="property-detail-title">
              <div className="modal-heading"><div><p className="eyebrow">PROPERTY DETAILS</p><h2 id="property-detail-title">{detailProperty.title}</h2><p className="page-description">{detailProperty.city}{detailProperty.locality ? ` · ${detailProperty.locality}` : ""}</p></div><button className="modal-close" onClick={() => setDetailProperty(null)} aria-label="Close">×</button></div>
              <div className="detail-summary-grid">
                <div><span>Price</span><strong>{formatPrice(detailProperty.price)}</strong></div><div><span>Status</span><strong><span className={`status-pill ${detailProperty.status.toLowerCase()}`}>{detailProperty.status}</span></strong></div>
                <div><span>Area</span><strong>{detailProperty.areaSqft.toLocaleString("en-IN")} sqft</strong></div><div><span>Configuration</span><strong>{detailProperty.bedrooms ?? 0} bed · {detailProperty.bathrooms ?? 0} bath</strong></div>
                <div><span>Transaction</span><strong>{detailProperty.transactionType}</strong></div><div><span>Category</span><strong>{detailProperty.propertyCategory} · {detailProperty.propertyType}</strong></div>
              </div>
              <section className="detail-description"><h3>Description</h3><p>{detailProperty.description || "No description provided."}</p></section>
              <div className="modal-actions"><button className="secondary-button" onClick={() => setDetailProperty(null)}>Close</button>{isOwner && <><button className="secondary-button" onClick={() => { setDetailProperty(null); setGalleryProperty(detailProperty); }}>Manage photos</button><button className="primary-button" onClick={() => { setDetailProperty(null); openEditForm(detailProperty); }}>Edit property</button></>}</div>
            </section>
          </div>
        )}

        {detailLead && (
          <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setDetailLead(null); }}>
            <section className="property-modal detail-modal" role="dialog" aria-modal="true" aria-labelledby="lead-detail-title">
              <div className="modal-heading"><div><p className="eyebrow">LEAD FOLLOW-UP</p><h2 id="lead-detail-title">{detailLead.customerName}</h2><p className="page-description">{detailLead.phone}{detailLead.email ? ` · ${detailLead.email}` : ""}</p></div><button className="modal-close" onClick={() => setDetailLead(null)} aria-label="Close">×</button></div>
              <div className="lead-workflow">
                <div className="lead-workflow-step"><span>Current status</span><strong><span className={`status-pill ${detailLead.status.toLowerCase()}`}>{detailLead.status.replaceAll("_", " ")}</span></strong></div>
                <div className="lead-workflow-step"><span>Next follow-up</span><strong>{detailLead.followUpAt ? new Date(detailLead.followUpAt).toLocaleString("en-IN", { dateStyle: "full", timeStyle: "short" }) : "Not scheduled"}</strong></div>
                <div className="lead-workflow-step"><span>Source</span><strong>{detailLead.source || "Not specified"}</strong></div>
                <div className="lead-workflow-step"><span>Interested property</span><strong>{properties.find((property) => property.id === detailLead.propertyId)?.title || "Not specified"}</strong></div>
              </div>
              <section className="detail-description"><h3>Enquiry notes</h3><p>{detailLead.message || "No notes added yet."}</p></section>
              <div className="modal-actions"><button className="secondary-button" onClick={() => setDetailLead(null)}>Close</button>{isOwner && <button className="primary-button" onClick={() => { setDetailLead(null); openEditLeadForm(detailLead); }}>Update follow-up</button>}</div>
            </section>
          </div>
        )}

        {showPropertyForm && (
          <div
            className="modal-backdrop"
            onMouseDown={(event) => {
              if (event.target === event.currentTarget) {
                setShowPropertyForm(false);
              }
            }}
          >
            <section
              className="property-modal"
              role="dialog"
              aria-modal="true"
              aria-labelledby="property-modal-title"
            >
              <div className="modal-heading">
                <div>
                  <p className="eyebrow">PROPERTY DETAILS</p>
                  <h2 id="property-modal-title">
                    {editingPropertyId ? "Edit property" : "Add new property"}
                  </h2>
                </div>
                <button
                  className="modal-close"
                  onClick={() => setShowPropertyForm(false)}
                  aria-label="Close"
                >
                  ×
                </button>
              </div>

              {propertyError && (
                <div className="error-message">{propertyError}</div>
              )}

              <form className="property-form" onSubmit={saveProperty}>
                <div className="form-group">
                  <label htmlFor="title">Property title *</label>
                  <input
                    id="title"
                    name="title"
                    value={form.title}
                    onChange={handleFormChange}
                    maxLength={200}
                    placeholder="Example: Premium 3BHK Apartment"
                    required
                  />
                </div>

                <div className="form-grid">
                  <div className="form-group">
                    <label htmlFor="transactionType">Listing type *</label>
                    <select
                      id="transactionType"
                      name="transactionType"
                      value={form.transactionType}
                      onChange={handleFormChange}
                    >
                      <option value="SALE">For Sale</option>
                      <option value="RENT">For Rent</option>
                    </select>
                  </div>
                  <div className="form-group">
                    <label htmlFor="propertyCategory">Category *</label>
                    <select
                      id="propertyCategory"
                      name="propertyCategory"
                      value={form.propertyCategory}
                      onChange={handleFormChange}
                    >
                      <option value="RESIDENTIAL">Residential</option>
                      <option value="COMMERCIAL">Commercial</option>
                    </select>
                  </div>
                  <div className="form-group">
                    <label htmlFor="propertyType">Property type *</label>
                    <select
                      id="propertyType"
                      name="propertyType"
                      value={form.propertyType}
                      onChange={handleFormChange}
                    >
                      <option value="APARTMENT">Apartment</option>
                      <option value="VILLA">Villa</option>
                      <option value="INDEPENDENT_HOUSE">Independent House</option>
                      <option value="PLOT">Plot</option>
                      <option value="OFFICE">Office</option>
                      <option value="SHOP">Shop</option>
                      <option value="OTHER">Other</option>
                    </select>
                  </div>
                  <div className="form-group">
                    <label htmlFor="status">Status *</label>
                    <select
                      id="status"
                      name="status"
                      value={form.status}
                      onChange={handleFormChange}
                    >
                      <option value="AVAILABLE">Available</option>
                      <option value="DRAFT">Draft</option>
                      <option value="SOLD">Sold</option>
                      <option value="RENTED">Rented</option>
                    </select>
                  </div>
                </div>

                <div className="form-grid">
                  <div className="form-group">
                    <label htmlFor="city">City *</label>
                    <input
                      id="city"
                      name="city"
                      value={form.city}
                      onChange={handleFormChange}
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label htmlFor="locality">Locality</label>
                    <input
                      id="locality"
                      name="locality"
                      value={form.locality}
                      onChange={handleFormChange}
                      placeholder="Example: Tolichowki"
                    />
                  </div>
                </div>

                <div className="form-grid">
                  <div className="form-group">
                    <label htmlFor="price">Price (₹) *</label>
                    <input
                      id="price"
                      name="price"
                      type="number"
                      min="1"
                      step="1"
                      value={form.price}
                      onChange={handleFormChange}
                      placeholder="Example: 12500000"
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label htmlFor="areaSqft">Area (sqft) *</label>
                    <input
                      id="areaSqft"
                      name="areaSqft"
                      type="number"
                      min="1"
                      value={form.areaSqft}
                      onChange={handleFormChange}
                      placeholder="Example: 2400"
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label htmlFor="bedrooms">Bedrooms</label>
                    <input
                      id="bedrooms"
                      name="bedrooms"
                      type="number"
                      min="0"
                      max="100"
                      value={form.bedrooms}
                      onChange={handleFormChange}
                    />
                  </div>
                  <div className="form-group">
                    <label htmlFor="bathrooms">Bathrooms</label>
                    <input
                      id="bathrooms"
                      name="bathrooms"
                      type="number"
                      min="0"
                      max="100"
                      value={form.bathrooms}
                      onChange={handleFormChange}
                    />
                  </div>
                </div>

                <div className="form-group">
                  <label htmlFor="description">Description</label>
                  <textarea
                    id="description"
                    name="description"
                    value={form.description}
                    onChange={handleFormChange}
                    maxLength={2000}
                    rows={4}
                    placeholder="Describe the property..."
                  />
                </div>

                <div className="modal-actions">
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() => setShowPropertyForm(false)}
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="primary-button"
                    disabled={savingProperty}
                  >
                    {savingProperty
                      ? "Saving..."
                      : editingPropertyId
                        ? "Save changes"
                        : "Add property"}
                  </button>
                </div>
              </form>
            </section>
          </div>
        )}

        {galleryProperty && (
          <PropertyImageGallery
            propertyId={galleryProperty.id}
            propertyTitle={galleryProperty.title}
            onClose={() => setGalleryProperty(null)}
          />
        )}
      </div>
    );
  }

  return (
    <main className="login-page">
      <section className="brand-panel">
        <div className="brand-header">
          <div className="brand-symbol">N</div>
          <h1>NEXZIAN</h1>
        </div>
        <div className="brand-message">
          <h2>
            Building Digital Success.
            <br />
            Empowering Real Estate.
          </h2>
          <div className="gold-line" />
          <p>
            Your business. Your properties. Your digital identity.
            <br />
            Everything your real estate business needs, in one place.
          </p>
        </div>
        <footer>© 2026 Nexzian Technologies</footer>
      </section>

      <section className="login-panel">
        <div className="login-content">
          <p className="welcome-label">{isRegistering ? "START YOUR WORKSPACE" : "WELCOME BACK"}</p>
          <h2>{isRegistering ? "Register your agency" : "Sign in to your account"}</h2>
          <p className="login-subtitle">{isRegistering ? "Create your agency and its first owner account." : "Manage your properties and grow your business."}</p>
          {isRegistering ? <form onSubmit={handleRegistration}>
            <div className="form-group"><label htmlFor="businessName">Agency name</label><input id="businessName" name="businessName" value={registrationForm.businessName} onChange={handleRegistrationChange} maxLength={150} required /></div>
            <div className="form-group"><label htmlFor="fullName">Your name</label><input id="fullName" name="fullName" value={registrationForm.fullName} onChange={handleRegistrationChange} maxLength={150} required /></div>
            <div className="form-group"><label htmlFor="registerEmail">Email address</label><input id="registerEmail" name="email" type="email" value={registrationForm.email} onChange={handleRegistrationChange} autoComplete="email" required /></div>
            <div className="form-group"><label htmlFor="registerPassword">Password</label><input id="registerPassword" name="password" type="password" value={registrationForm.password} onChange={handleRegistrationChange} minLength={8} autoComplete="new-password" required /></div>
            <div className="form-grid"><div className="form-group"><label htmlFor="phone">Phone</label><input id="phone" name="phone" value={registrationForm.phone} onChange={handleRegistrationChange} maxLength={20} /></div><div className="form-group"><label htmlFor="city">City</label><input id="city" name="city" value={registrationForm.city} onChange={handleRegistrationChange} maxLength={100} /></div></div>
            <div className="form-group"><label htmlFor="locality">Locality</label><input id="locality" name="locality" value={registrationForm.locality} onChange={handleRegistrationChange} maxLength={100} /></div>
            {error && <div className="error-message" role="alert">{error}</div>}
            <button className="login-button" type="submit" disabled={registrationLoading}>{registrationLoading ? "Registering..." : "Create agency account"}</button>
          </form> : <form onSubmit={handleLogin}>
            <div className="form-group">
              <label htmlFor="email">Email address</label>
              <input
                id="email"
                type="email"
                placeholder="Enter your email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="username"
                required
              />
            </div>
            <div className="form-group">
              <label htmlFor="password">Password</label>
              <input
                id="password"
                type="password"
                placeholder="Enter your password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="current-password"
                required
              />
            </div>
            {error && (
              <div className="error-message" role="alert">
                {error}
              </div>
            )}
            <button className="login-button" type="submit" disabled={loading}>
              {loading ? "Signing in..." : "Sign In"}
            </button>
          </form>}
          {registrationMessage && <div className="success-message auth-success" role="status">{registrationMessage}</div>}
          <p className="register-text">
            {isRegistering ? "Already registered? " : "New to Nexzian? "}
            <button type="button" className="auth-link" onClick={() => { setIsRegistering(!isRegistering); setError(""); }}>{isRegistering ? "Sign in" : "Register your agency"}</button>
          </p>
          <p className="secure-text">
            Secure access for your real estate business
          </p>
        </div>
      </section>
    </main>
  );
}

function App() {
  const publicSlug = new URLSearchParams(window.location.search).get("agency")
    ?? window.location.pathname.match(/^\/public\/([^/]+)/)?.[1];

  return publicSlug ? <PublicAgencySite slug={publicSlug} /> : <WorkspaceApp />;
}

export default App;

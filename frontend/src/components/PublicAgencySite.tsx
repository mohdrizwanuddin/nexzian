import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../services/api";
import "./PublicAgencySite.css";

interface Agency {
  businessName: string;
  websiteSlug: string;
  phone?: string;
  city?: string;
  locality?: string;
}

interface PublicProperty {
  id: string;
  title: string;
  description?: string;
  transactionType: string;
  propertyCategory: string;
  propertyType: string;
  city: string;
  locality?: string;
  price: number;
  areaSqft: number;
  bedrooms?: number;
  bathrooms?: number;
}

export default function PublicAgencySite({ slug }: { slug: string }) {
  const [agency, setAgency] = useState<Agency | null>(null);
  const [properties, setProperties] = useState<PublicProperty[]>([]);
  const [selectedProperty, setSelectedProperty] = useState<PublicProperty | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");

  useEffect(() => {
    void Promise.all([
      apiRequest<Agency>(`/tenants/public/${encodeURIComponent(slug)}`),
      apiRequest<PublicProperty[]>(`/public/${encodeURIComponent(slug)}/properties`),
    ]).then(([agencyData, propertyData]) => {
      setAgency(agencyData);
      setProperties(propertyData);
    }).catch((reason: unknown) => {
      setError(reason instanceof Error ? reason.message : "Unable to load this agency website.");
    }).finally(() => setLoading(false));
  }, [slug]);

  const filteredProperties = useMemo(() => {
    const term = search.trim().toLowerCase();
    return properties.filter((property) => !term || [property.title, property.city, property.locality, property.propertyType]
      .filter(Boolean).some((value) => value!.toLowerCase().includes(term)));
  }, [properties, search]);

  const formatPrice = (price: number) => new Intl.NumberFormat("en-IN", {
    style: "currency", currency: "INR", maximumFractionDigits: 0,
  }).format(price);

  if (loading) return <main className="public-loading"><div className="loading-spinner" /><p>Loading agency website...</p></main>;
  if (error || !agency) return <main className="public-loading"><h1>Agency website unavailable</h1><p>{error || "This agency could not be found."}</p></main>;

  return <main className="public-site">
    <header className="public-nav">
      <a className="public-brand" href="#top"><span>N</span>{agency.businessName}</a>
      <nav><a href="#properties">Properties</a><a href="#about">About</a><a className="public-contact-link" href={`tel:${agency.phone || ""}`}>Contact us</a></nav>
    </header>
    <section className="public-hero" id="top">
      <p className="public-eyebrow">REAL ESTATE · {agency.city?.toUpperCase() || "INDIA"}</p>
      <h1>A better way to find your <em>next place.</em></h1>
      <p>Discover thoughtfully selected properties from {agency.businessName}, in {agency.locality || agency.city || "your city"} and beyond.</p>
      <a className="public-primary" href="#properties">Explore properties <span>↓</span></a>
    </section>
    <section className="public-metrics" id="about"><div><strong>{properties.length}</strong><span>Published listings</span></div><div><strong>{agency.city || "India"}</strong><span>Local market expertise</span></div><div><strong>Direct</strong><span>Agency contact</span></div></section>
    <section className="public-listings" id="properties">
      <div className="public-section-heading"><div><p className="public-eyebrow">CURATED COLLECTION</p><h2>Properties worth seeing.</h2></div><input type="search" placeholder="Search location or property..." value={search} onChange={(event) => setSearch(event.target.value)} /></div>
      {filteredProperties.length === 0 ? <div className="public-empty"><h3>No matching properties</h3><p>Try another location or property type.</p></div> : <div className="public-property-grid">{filteredProperties.map((property, index) => <article className="public-property-card" key={property.id}>
        <div className={`public-property-image image-${index % 3}`}><span>{property.transactionType}</span><div className="public-image-mark">⌂</div></div>
        <div className="public-property-content"><p>{property.propertyType} · {property.city}</p><h3>{property.title}</h3><strong>{formatPrice(property.price)}</strong><div className="public-property-meta"><span>{property.bedrooms ?? 0} beds</span><span>{property.bathrooms ?? 0} baths</span><span>{property.areaSqft.toLocaleString("en-IN")} sqft</span></div><button onClick={() => setSelectedProperty(property)}>View property <span>→</span></button></div>
      </article>)}</div>}
    </section>
    <section className="public-contact"><p className="public-eyebrow">LET'S TALK</p><h2>Looking for something specific?</h2><p>Speak directly with {agency.businessName} to discuss your next move.</p><a className="public-primary" href={`tel:${agency.phone || ""}`}>{agency.phone || "Contact agency"}</a></section>
    <footer className="public-footer"><span>© {new Date().getFullYear()} {agency.businessName}</span><span>{agency.locality ? `${agency.locality}, ` : ""}{agency.city}</span></footer>
    {selectedProperty && <div className="public-modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setSelectedProperty(null); }}><article className="public-property-modal" role="dialog" aria-modal="true"><button className="public-modal-close" onClick={() => setSelectedProperty(null)}>×</button><p className="public-eyebrow">{selectedProperty.transactionType} · {selectedProperty.propertyType}</p><h2>{selectedProperty.title}</h2><p className="public-location">{selectedProperty.locality ? `${selectedProperty.locality}, ` : ""}{selectedProperty.city}</p><div className="public-modal-price">{formatPrice(selectedProperty.price)}</div><div className="public-modal-meta"><span>{selectedProperty.bedrooms ?? 0} bedrooms</span><span>{selectedProperty.bathrooms ?? 0} bathrooms</span><span>{selectedProperty.areaSqft.toLocaleString("en-IN")} sqft</span></div><p className="public-modal-description">{selectedProperty.description || "Contact the agency for further property details."}</p><a className="public-primary" href={`tel:${agency.phone || ""}`}>Enquire about this property</a></article></div>}
  </main>;
}

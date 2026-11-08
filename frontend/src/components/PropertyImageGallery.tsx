
import { useCallback, useEffect, useRef, useState } from "react";
import "./PropertyImageGallery.css";
import { apiBlobRequest, apiRequest } from "../services/api";

interface PropertyImage {
  id: string;
  propertyId: string;
  originalFileName: string;
  contentType: string;
  fileSize: number;
  featured: boolean;
  imageUrl: string;
  createdAt: string;
}

interface PropertyImageGalleryProps {
  propertyId: string;
  propertyTitle: string;
  onClose: () => void;
}

const MAX_IMAGES = 20;
const MAX_FILE_SIZE = 5 * 1024 * 1024;
const ALLOWED_TYPES = ["image/jpeg", "image/png", "image/webp"];

function PropertyImageGallery({
  propertyId,
  propertyTitle,
  onClose,
}: PropertyImageGalleryProps) {
  const [images, setImages] = useState<PropertyImage[]>([]);
  const [imageUrls, setImageUrls] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [workingImageId, setWorkingImageId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [previewImage, setPreviewImage] = useState<PropertyImage | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  const loadImages = useCallback(async () => {
    try {
      const data = await apiRequest<PropertyImage[]>(`/properties/${propertyId}/images`);

      setImages(Array.isArray(data) ? data : []);
      setError("");
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Unable to load property images."
      );
    } finally {
      setLoading(false);
    }
  }, [propertyId]);

  // Load the gallery when the selected property changes.
  useEffect(() => {
    let cancelled = false;

    const fetchImages = async () => {
      try {
        const data = await apiRequest<PropertyImage[]>(`/properties/${propertyId}/images`);

        if (!cancelled) {
          setImages(Array.isArray(data) ? data : []);
          setError("");
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof Error
              ? err.message
              : "Unable to load property images."
          );
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    void fetchImages();

    return () => {
      cancelled = true;
    };
  }, [propertyId]);

  // Load image files and create browser object URLs.
  useEffect(() => {
    let cancelled = false;
    const createdUrls: string[] = [];

    const loadImageFiles = async () => {
      if (images.length === 0) {
        await Promise.resolve();

        if (!cancelled) {
          setImageUrls({});
        }
        return;
      }

      try {
        const entries = await Promise.all(
          images.map(async (image) => {
            const blob = await apiBlobRequest(
              `/properties/${propertyId}/images/${image.id}/file`
            );
            const url = URL.createObjectURL(blob);

            if (cancelled) {
              URL.revokeObjectURL(url);
              return [image.id, ""] as const;
            }

            createdUrls.push(url);
            return [image.id, url] as const;
          })
        );

        if (!cancelled) {
          setImageUrls(
            Object.fromEntries(
              entries.filter(([, url]) => url !== "")
            )
          );
        }
      } catch (err) {
        createdUrls.forEach((url) => URL.revokeObjectURL(url));
        createdUrls.length = 0;

        if (!cancelled) {
          setImageUrls({});
          setError(
            err instanceof Error
              ? err.message
              : "Unable to display images."
          );
        }
      }
    };

    void loadImageFiles();

    return () => {
      cancelled = true;
      createdUrls.forEach((url) => URL.revokeObjectURL(url));
    };
  }, [images, propertyId]);

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setPreviewImage(null);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, []);

  const uploadImages = async (selectedFiles: FileList | null) => {
    if (!selectedFiles || selectedFiles.length === 0) return;

    const files = Array.from(selectedFiles);
    const remainingSlots = MAX_IMAGES - images.length;

    if (files.length > remainingSlots) {
      setError(
        `You can upload only ${remainingSlots} more image(s). Maximum allowed is ${MAX_IMAGES}.`
      );
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
      return;
    }

    for (const file of files) {
      if (!ALLOWED_TYPES.includes(file.type)) {
        setError(
          `${file.name} is not supported. Please select JPEG, PNG or WEBP images.`
        );
        if (fileInputRef.current) {
          fileInputRef.current.value = "";
        }
        return;
      }

      if (file.size > MAX_FILE_SIZE) {
        setError(`${file.name} exceeds the 5 MB file size limit.`);
        if (fileInputRef.current) {
          fileInputRef.current.value = "";
        }
        return;
      }
    }

    setUploading(true);
    setError("");
    setSuccess("");

    let uploadedCount = 0;

    try {
      for (const file of files) {
        const formData = new FormData();
        formData.append("file", file);

        await apiRequest(`/properties/${propertyId}/images`, {
          method: "POST",
          body: formData,
        });

        uploadedCount++;
      }

      setSuccess(
        `${uploadedCount} image${uploadedCount === 1 ? "" : "s"} uploaded successfully.`
      );

      await loadImages();
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Unable to upload images."
      );

      if (uploadedCount > 0) {
        await loadImages();
      }
    } finally {
      setUploading(false);

      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    }
  };

  const setFeaturedImage = async (imageId: string) => {
    setWorkingImageId(imageId);
    setError("");
    setSuccess("");

    try {
      await apiRequest(`/properties/${propertyId}/images/${imageId}/featured`, {
        method: "PUT",
      });

      setSuccess("Featured image updated successfully.");
      await loadImages();
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Unable to update featured image."
      );
    } finally {
      setWorkingImageId(null);
    }
  };

  const deleteImage = async (image: PropertyImage) => {
    if (!window.confirm(`Delete "${image.originalFileName}"?`)) {
      return;
    }

    setWorkingImageId(image.id);
    setError("");
    setSuccess("");

    try {
      await apiRequest(`/properties/${propertyId}/images/${image.id}`, {
        method: "DELETE",
      });

      setSuccess("Image deleted successfully.");
      await loadImages();
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Unable to delete image."
      );
    } finally {
      setWorkingImageId(null);
    }
  };

  const formatFileSize = (bytes: number) => {
    return `${(bytes / 1024).toFixed(1)} KB`;
  };

  return (
    <div
      className="gallery-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <section
        className="gallery-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="gallery-title"
      >
        <div className="gallery-header">
          <div>
            <p className="eyebrow">PROPERTY MEDIA</p>
            <h2 id="gallery-title">Property photo gallery</h2>
            <p className="gallery-property-name">{propertyTitle}</p>
          </div>

          <button
            className="modal-close"
            onClick={onClose}
            aria-label="Close gallery"
          >
            ×
          </button>
        </div>

        <div className="gallery-toolbar">
          <div>
            <strong>{images.length} / {MAX_IMAGES} photos</strong>
            <p>JPEG, PNG or WEBP · Maximum 5 MB per image</p>
          </div>

          <button
            className="primary-button"
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading || images.length >= MAX_IMAGES}
          >
            {uploading ? "Uploading..." : "+ Upload photos"}
          </button>

          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            hidden
            onChange={(event) => void uploadImages(event.target.files)}
          />
        </div>

        {success && (
          <div className="success-message" role="status">
            {success}
          </div>
        )}

        {error && (
          <div className="error-message" role="alert">
            {error}
          </div>
        )}

        {loading ? (
          <div className="gallery-empty">
            <div className="loading-spinner" />
            <p>Loading property photos...</p>
          </div>
        ) : images.length === 0 ? (
          <div className="gallery-empty">
            <div className="empty-icon">▧</div>
            <h3>No photos uploaded yet</h3>
            <p>Add property photos to make this listing more attractive.</p>

            <button
              className="primary-button"
              onClick={() => fileInputRef.current?.click()}
              disabled={uploading}
            >
              Upload first photo
            </button>
          </div>
        ) : (
          <div className="gallery-grid">
            {images.map((image) => (
              <article className="gallery-card" key={image.id}>
                <div className="gallery-image">
                  {imageUrls[image.id] ? (
                    <button
                      className="gallery-image-preview"
                      onClick={() => setPreviewImage(image)}
                      aria-label={`View ${image.originalFileName} full size`}
                    >
                      <img
                        src={imageUrls[image.id]}
                        alt={image.originalFileName}
                      />
                    </button>
                  ) : (
                    <div className="gallery-image-loading">
                      Loading image...
                    </div>
                  )}

                  {image.featured && (
                    <span className="featured-label">
                      FEATURED PHOTO
                    </span>
                  )}
                </div>

                <div className="gallery-card-details">
                  <strong title={image.originalFileName}>
                    {image.originalFileName}
                  </strong>
                  <span>{formatFileSize(image.fileSize)}</span>
                </div>

                <div className="gallery-card-actions">
                  {!image.featured ? (
                    <button
                      className="action-button"
                      onClick={() => void setFeaturedImage(image.id)}
                      disabled={workingImageId !== null}
                    >
                      {workingImageId === image.id
                        ? "Updating..."
                        : "Set as featured"}
                    </button>
                  ) : (
                    <span className="featured-status">
                      Main photo
                    </span>
                  )}

                  <button
                    className="action-button delete-action"
                    onClick={() => void deleteImage(image)}
                    disabled={workingImageId !== null}
                  >
                    Delete
                  </button>
                </div>
              </article>
            ))}
          </div>
        )}

        <div className="gallery-footer">
          <button className="secondary-button" onClick={onClose}>
            Close gallery
          </button>
        </div>
      </section>
      {previewImage && imageUrls[previewImage.id] && (
        <div
          className="image-lightbox"
          role="dialog"
          aria-modal="true"
          aria-label={`Full-size preview of ${previewImage.originalFileName}`}
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setPreviewImage(null);
          }}
        >
          <button
            className="image-lightbox-close"
            onClick={() => setPreviewImage(null)}
            aria-label="Close full-size preview"
          >
            ×
          </button>
          <img
            src={imageUrls[previewImage.id]}
            alt={previewImage.originalFileName}
          />
          <span>{previewImage.originalFileName}</span>
        </div>
      )}
    </div>
  );
}

export default PropertyImageGallery;

package com.macrotel.rapidstylers.catalog.service;

import com.macrotel.rapidstylers.catalog.entity.IdentificationEntity;
import com.macrotel.rapidstylers.catalog.entity.ServiceEntity;
import com.macrotel.rapidstylers.catalog.repo.BlogPostRepo;
import com.macrotel.rapidstylers.catalog.repo.IdentificationRepo;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The catalog domain's front door for other domains: service types,
 * accepted ID types and blog posts. Only catalog code reaches its repos directly.
 */
@Service
public class CatalogRecords {

    @Autowired
    ServiceRepo serviceRepo;
    @Autowired
    IdentificationRepo identificationRepo;
    @Autowired
    BlogPostRepo blogPostRepo;

    public CatalogRecords() {
    }

    public CatalogRecords(ServiceRepo serviceRepo, IdentificationRepo identificationRepo) {
        this.serviceRepo = serviceRepo;
        this.identificationRepo = identificationRepo;
    }

    public Optional<ServiceEntity> findServiceById(Long serviceId) {
        return serviceRepo.findById(serviceId);
    }

    public Optional<IdentificationEntity> findIdentificationById(Long identificationId) {
        return identificationRepo.findById(identificationId);
    }

    /** How many service-type and blog images point at this Cloudinary asset. */
    public long imageReferences(String fragment) {
        return serviceRepo.countByServiceImageUrlContaining(fragment)
                + blogPostRepo.countByImageUrlContaining(fragment);
    }
}

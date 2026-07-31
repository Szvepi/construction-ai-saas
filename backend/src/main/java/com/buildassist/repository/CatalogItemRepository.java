package com.buildassist.repository;

import com.buildassist.model.CatalogItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogItemRepository extends JpaRepository<CatalogItem, Long> {

    List<CatalogItem> findByUserId(Long userId);
}

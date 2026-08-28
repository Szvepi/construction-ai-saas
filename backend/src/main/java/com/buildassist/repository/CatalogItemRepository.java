package com.buildassist.repository;

import com.buildassist.model.CatalogItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogItemRepository extends JpaRepository<CatalogItem, Long> {

    List<CatalogItem> findAllByUserId(Long userId);
}

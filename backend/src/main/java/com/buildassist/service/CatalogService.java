package com.buildassist.service;

import com.buildassist.dto.CatalogDtos.CatalogItemResponse;
import com.buildassist.dto.CatalogDtos.CreateCatalogItemRequest;
import com.buildassist.dto.CatalogDtos.UpdateCatalogItemRequest;
import com.buildassist.model.CatalogItem;
import com.buildassist.model.User;
import com.buildassist.repository.CatalogItemRepository;
import com.buildassist.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CatalogService {

    private final CatalogItemRepository catalogItemRepository;

    private final UserRepository userRepository;

    public CatalogService(CatalogItemRepository catalogItemRepository, UserRepository userRepository) {
        this.catalogItemRepository = catalogItemRepository;
        this.userRepository = userRepository;
    }

    public List<CatalogItemResponse> findItemsByUserId(Long userId) {
        return catalogItemRepository.findAllByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CatalogItemResponse createItem(Long userId, CreateCatalogItemRequest request) {
        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty()) {
            throw new IllegalArgumentException("User not found");
        }

        CatalogItem item = new CatalogItem(
                user.get(),
                request.name(),
                request.unitPrice(),
                request.unit()
        );
        item.setCalculationStrategy(request.calculationStrategy());

        CatalogItem saved = catalogItemRepository.save(item);
        return toResponse(saved);
    }

    @Transactional
    public CatalogItemResponse updateItem(Long userId, Long itemId, UpdateCatalogItemRequest request) {
        CatalogItem item = catalogItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Catalog item not found"));

        if (!item.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to update this item");
        }

        item.setName(request.name());
        item.setUnitPrice(request.unitPrice());
        item.setUnit(request.unit());
        item.setCalculationStrategy(request.calculationStrategy());

        CatalogItem updated = catalogItemRepository.save(item);
        return toResponse(updated);
    }

    @Transactional
    public void deleteItem(Long userId, Long itemId) {
        CatalogItem item = catalogItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Catalog item not found"));

        if (!item.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to delete this item");
        }

        catalogItemRepository.delete(item);
    }

    private CatalogItemResponse toResponse(CatalogItem item) {
        return new CatalogItemResponse(
                item.getId(),
                item.getName(),
                item.getUnitPrice(),
                item.getUnit(),
                item.getCalculationStrategy(),
                item.getCreatedAt()
        );
    }
}

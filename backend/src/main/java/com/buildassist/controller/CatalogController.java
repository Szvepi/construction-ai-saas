package com.buildassist.controller;

import com.buildassist.dto.CatalogDtos.CatalogItemResponse;
import com.buildassist.dto.CatalogDtos.CreateCatalogItemRequest;
import com.buildassist.dto.CatalogDtos.UpdateCatalogItemRequest;
import com.buildassist.security.SecurityUtils;
import com.buildassist.service.CatalogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catalog", description = "Manage catalog items (price book)")
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<CatalogItemResponse> listItems() {
        return catalogService.findItemsByUserId(SecurityUtils.currentUserId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItemResponse createItem(@RequestBody CreateCatalogItemRequest request) {
        return catalogService.createItem(SecurityUtils.currentUserId(), request);
    }

    @PutMapping("/{id}")
    public CatalogItemResponse updateItem(@PathVariable Long id, @RequestBody UpdateCatalogItemRequest request) {
        return catalogService.updateItem(SecurityUtils.currentUserId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable Long id) {
        catalogService.deleteItem(SecurityUtils.currentUserId(), id);
    }
}

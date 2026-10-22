package com.buildassist.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "company_profiles")
public class CompanyProfile {

    public static final String DEFAULT_OFFER_TERMS = """
            Jelen árajánlat a kiküldéstől számított 15 napig érvényes.

            A megadott árak a levélben leírt paraméterek alapján számított tájékoztató jellegű becslések. A végleges árajánlatot a helyszíni felmérést követően rögzítjük.
            """.strip();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "legal_form", nullable = false)
    private String legalForm;

    @Column(name = "tax_number", nullable = false)
    private String taxNumber;

    @Column(name = "eu_tax_number")
    private String euTaxNumber;

    @Column(name = "vat_registered", nullable = false)
    private boolean vatRegistered;

    @Column(name = "offer_terms", columnDefinition = "TEXT")
    private String offerTerms = DEFAULT_OFFER_TERMS;

    @Column(name = "offeror_name")
    private String offerorName;

    @Column(name = "offeror_phone")
    private String offerorPhone;

    @Column(nullable = false, length = 100)
    private String country = "Magyarország";

    @Column(name = "postal_code", nullable = false)
    private String postalCode;

    @Column(nullable = false)
    private String city;

    @Column(name = "street_name", nullable = false)
    private String streetName;

    @Column(name = "street_type", nullable = false, length = 100)
    private String streetType;

    @Column(name = "house_number", nullable = false, length = 50)
    private String houseNumber;

    @Column(length = 100)
    private String building;

    @Column(length = 100)
    private String staircase;

    @Column(name = "floor_number", length = 50)
    private String floor;

    @Column(length = 50)
    private String door;
}

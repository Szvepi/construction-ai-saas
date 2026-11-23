package com.buildassist.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "company_profiles")
public class CompanyProfile {

    public static final String DEFAULT_OFFER_TERMS = """
            Ajánlatunk a megküldéstől számított 15 naptári napig érvényes.
            
            Az ajánlatot a rendelkezésünkre bocsátott információk, a megadott mennyiségek és az ismert műszaki követelmények alapján állítottuk össze.
            
            A helyszíni adottságok, a tényleges mennyiségek vagy a műszaki tartalom változása esetén az ajánlat módosítása válhat szükségessé. Az esetleges változásokat a kivitelezés megkezdése előtt egyeztetjük a megrendelővel.
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

    @Column(name = "tax_regime", nullable = false, length = 32)
    private String taxRegime = "AAM";

    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "offer_terms", columnDefinition = "TEXT")
    private String offerTerms = DEFAULT_OFFER_TERMS;

    @Column(name = "offeror_name")
    private String offerorName;

    @Column(name = "offeror_phone")
    private String offerorPhone;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

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

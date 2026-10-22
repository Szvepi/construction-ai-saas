ALTER TABLE company_profiles
    ADD COLUMN offer_terms TEXT;

UPDATE company_profiles
SET offer_terms = E'Jelen árajánlat a kiküldéstől számított 15 napig érvényes.\n\nA megadott árak a levélben leírt paraméterek alapján számított tájékoztató jellegű becslések. A végleges árajánlatot a helyszíni felmérést követően rögzítjük.';

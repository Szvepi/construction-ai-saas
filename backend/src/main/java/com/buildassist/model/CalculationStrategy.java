package com.buildassist.model;

public enum CalculationStrategy {

    DIRECT,              // Nincs módosítás (pl. fűnyírás m2-re)
    WALL_SURFACE_3X,     // Alapterületből csinál falfelületet (Festő)
    ROOM_PERIMETER,      // Alapterületből szoba kerületet számol (Szegélyléc, lábazat)
    VOLUME_BY_THICKNESS, // Területből köbmétert csinál fix vastagsággal (Kőműves)
    WASTE_PERCENTAGE_10  // Automatikusan rátesz 10% anyagveszteséget (Burkoló)
}

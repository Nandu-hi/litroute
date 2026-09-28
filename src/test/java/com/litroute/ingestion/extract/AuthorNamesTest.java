package com.litroute.ingestion.extract;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class AuthorNamesTest {

    @Test
    public void splitsOnCommasAndAndAmpersandAndDropsPlaceholders() {
        assertEquals(List.of("Gilberto Gomes", "Luis Silva", "Elton Bauer"), AuthorNames.parse("Gilberto Gomes, Luis Silva and Elton Bauer"));
        assertEquals(List.of("R. Abduh", "M. A. Viqolbi"), AuthorNames.parse("R. Abduh, M. A. Viqolbi & others"));
        assertEquals(List.of("A. Bozak", "O. Beyaztunç"), AuthorNames.parse("A. Bozak and O. Beyaztunç"));
        assertEquals(List.of("Imtiyaz Akbar Najar", "Shanshan Tang"), AuthorNames.parse("Imtiyaz Akbar Najar … Shanshan Tang"));
    }

    @Test
    public void understandsParticlesHyphensAndInitials() {
        assertEquals(List.of("John W. van de Lindt", "Amal El Attari", "Ya-Dong Zhou", "C.S. Cai"),
                AuthorNames.parse("John W. van de Lindt, Amal El Attari, Ya-Dong Zhou, C.S. Cai"));
    }

    @Test
    public void rejectsLinesThatAreNotAuthorLists() {
        assertEquals(List.of(), AuthorNames.parse("Journal of Infrastructure Preservation and Resilience 2025, 6:5 | Published on: 26 January 2025"));
        assertEquals(List.of(), AuthorNames.parse("Ocean Engineering, Volume 311, Part 2, August 2024, Article Number 118952"));
        assertEquals(List.of(), AuthorNames.parse("In current dynamic time history assessment protocols, ground motion recordings are mostly oriented"));
        assertEquals(List.of(), AuthorNames.parse("REVIEW"));
        assertEquals(List.of(), AuthorNames.parse("Full Text | PDF"));
        assertEquals(List.of(), AuthorNames.parse(""));
    }
}

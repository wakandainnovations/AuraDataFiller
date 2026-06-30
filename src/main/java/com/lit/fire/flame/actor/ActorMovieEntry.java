package com.lit.fire.flame.actor;

/**
 * One movie entry parsed from a sacnilk actor filmography page
 * (e.g. https://www.sacnilk.com/news/List_Of_All_Akshay_Kumra_Movies).
 */
public record ActorMovieEntry(
    String actorName,
    String movieName,
    String releaseDate,     // 4-digit year or YYYY-MM-DD; null if not determinable
    String language,
    String genre,
    String director,
    String roleDescription, // maps to character_name column
    String sacnilkMovieSlug // slug if the row links to a /movie/ page; else null
) {}

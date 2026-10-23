# movie_marketing_tactics manual validation — progress

Hand-validation of the AuraLLM-generated rows in `movie_marketing_tactics`, 100 movies per
session, 5 at a time. Each movie is web-researched; rows are kept, rewritten, deleted or added.

## Rules (agreed with the user 2026-10-22)

- **Delete unless sourced.** A row stays only if a source (news article, Wikipedia, official
  YouTube/social post, trade press) shows the tactic actually happened for *this* film.
  `tactic_details` is rewritten to state only the facts found (date, venue, people), with the
  source named in brackets. Generic filler ("TV spots on Sun TV", "print ads in Daily Thanthi",
  "standees in lobbies") is deleted when nothing confirms it.
- **Wrong film.** Many rows describe a different, more famous film with a similar title
  (e.g. `Furioso` 2026 German → rows were about *Furiosa*). These are deleted; real tactics for
  the actual film are added only if sources exist.
- **Orphan / wrong-year units.** A (movie_name, release_year) with no matching row in
  `movies_data_collection` / `managed_entities` whose rows describe a film from another year
  (e.g. `Passages` 2026 = Ira Sachs' 2023 film, which has its own 2023 unit) is deleted.
- **Missing tactics** found while researching are added (via the same upsert), keyed to the
  existing (movie_name, release_year, language) of the unit — but never to `unknown` (see below).
- No source found → leave the movie with no rows. Never guess.
- A "movie" (unit) = one (release_year, movie_name) — all its language variants
  (e.g. `tamil` + `unknown`) are checked together.
- **Fix `unknown` language (added 2026-10-23).** After a unit's rows are validated, any rows
  still on `language = 'unknown'` are moved to the real language, if it became known during
  the session:
  1. First look in `movie_marketing_tactics` for the same movie_name + release_year under
     a known language. If it exists, use that language: the `unknown` rows are merged into it,
     and where both have the same `sub_classification_number` the known-language row is kept
     (once both are validated they say the same thing).
  2. Otherwise take the language from `movies_data_collection` for the same name and year,
     unless the research showed something different (e.g. `Vrusshabha` 2025: MDC says
     kannada, sources say a Malayalam-Telugu bilingual led by Mohanlal → `malayalam`).
  3. Always use the **full lowercase language name**: `english`, `tamil`, `telugu`, `hindi`,
     `kannada`, `malayalam`, `japanese`, `chinese` (not `mandarin chinese`/`cn`), `latvian`
     (not `lv`), `danish` (not `da`), `czech` (not `cs`), `polish` (not `pl`), `dutch` (not
     `nl`), `swedish`, `turkish`, `thai`, … Any short code or capitalised value (`Kannada`)
     on a unit being validated is converted the same way, even when it isn't `unknown`.
  4. If the language still isn't known, leave the rows on `unknown`; never guess.
- **One set of rows per release language (added 2026-10-23).** A film released in several
  languages (e.g. `Jockey` 2026 in Tamil *and* Telugu, `Kantara: Chapter 1` in seven) gets
  rows under **each** language, because the tactics aimed at each language's audience differ.
  - Work out the release languages from the research (Wikipedia, trade press), plus
    `movies_data_collection` (e.g. MDC `telugu, tamil`).
  - A tactic goes under a language only if a source shows it ran for that language version,
    and its `tactic_details` gives the language-specific facts. Examples: Kantara's Hindi
    trailer was launched by Hrithik Roshan and the Telugu one by Prabhas; Thandel's Hindi
    trailer was launched by Aamir Khan in Mumbai; Chhaava's Telugu dub trailer was released by
    Geetha Arts. A teaser "released in Telugu, Tamil, Malayalam, Kannada and Hindi" gives a row
    in each of those five.
  - Tactics tied to the original version stay under the original language only. These include
    festival premieres, an English-language Comic-Con panel, and a Tamil audio launch in
    Chennai.
  - A tactic aimed at a dubbed release belongs to the dub's language, not the original. For
    example, A24's English-dub trailer for *Ne Zha 2* is filed under `english`.
  - If a language version exists but nothing was sourced for it, it gets no rows (e.g.
    *Kuberaa* Tamil, *Vidaamuyarchi* Telugu "Patudala"). Never copy rows to a language
    without a source.
  - Rows filed under a wrong market language are relabelled to the language whose audience
    the tactic actually targeted (e.g. `Ghostlight` 2025 `th` → `english`: its Sundance
    premiere was for the English original).

  Relabel SQL (fill `rl` with the session's resolved units; `f` = current language):

  ```sql
  CREATE TEMP TABLE rl(n text, y text, f text, t text);
  INSERT INTO rl VALUES ('Movie', '2025', 'unknown', 'telugu');  -- one row per relabel
  INSERT INTO movie_marketing_tactics (movie_name, release_year, language,
    main_classification_number, main_classification_name, sub_classification_number,
    sub_classification_name, tactic_details, updated_at)
  SELECT m.movie_name, m.release_year, r.t, m.main_classification_number,
    m.main_classification_name, m.sub_classification_number, m.sub_classification_name,
    m.tactic_details, now()
  FROM movie_marketing_tactics m
  JOIN rl r ON r.n = m.movie_name AND r.y = m.release_year AND r.f = m.language
  ON CONFLICT (movie_name, release_year, language, sub_classification_number) DO NOTHING;
  DELETE FROM movie_marketing_tactics m USING rl r
  WHERE r.n = m.movie_name AND r.y = m.release_year AND r.f = m.language;
  ```

  Then add the extra-language rows with the normal upsert below, one per (language, tactic).

## Order and cursor

Phase 1 walks existing units in this order:

```sql
SELECT release_year, movie_name FROM movie_marketing_tactics
GROUP BY 1, 2 ORDER BY release_year DESC, movie_name COLLATE "C"
```

Next batch after the cursor (Y, N):

```sql
SELECT release_year, movie_name FROM movie_marketing_tactics
WHERE release_year < 'Y' OR (release_year = 'Y' AND movie_name COLLATE "C" > 'N')
GROUP BY 1, 2 ORDER BY release_year DESC, movie_name COLLATE "C" LIMIT 100
```

Phase 2 (after phase 1 finishes): add tactics for movies in `india_movies_data_collection`
and `india_prev_movies_data_collection` that have no rows in `movie_marketing_tactics`
(523 + 3,890 distinct (name, year, language) as of 2026-10-22).

Note: a `TRUNCATE` + re-run of `--marketing-tactics-scan-once` would wipe this work.

**Mark the batch validated (last step of every session).** Insert all 100 units into
`movie_marketing_tactics_validated` — including units left with no rows — so the scan never
picks them up again. Without this step, the 180-day recheck would rewrite a unit with raw LLM
output, or re-fill a unit validated as having no sourced tactics:

```sql
INSERT INTO movie_marketing_tactics_validated (movie_name, release_year)
VALUES ('Movie', '2025'), ...          -- the session's 100 (movie_name, release_year) units
ON CONFLICT DO NOTHING;
```

Insert/overwrite a verified row (main classification number/name from
`MarketingTacticTaxonomy.java`; the unit's existing `language` value is reused unless it is
`unknown` and the real language is known — then write the real language directly):

```sql
INSERT INTO movie_marketing_tactics (movie_name, release_year, language,
  main_classification_number, main_classification_name,
  sub_classification_number, sub_classification_name, tactic_details, updated_at)
VALUES ('Movie', '2025', 'tamil', 1, 'Trailer & Video Marketing', 2, 'Theatrical Trailers',
  ARRAY['<verified facts with dates> (Source)'], now())
ON CONFLICT (movie_name, release_year, language, sub_classification_number)
DO UPDATE SET tactic_details = EXCLUDED.tactic_details, updated_at = now();
```

## Current state

- Phase: 1
- Cursor (last fully validated unit): `2025 | Wallace & Gromit: Vengeance Most Fowl`
- Units validated so far: 200 (of ~68,000)

## Prompt to start the next session

> Continue the movie_marketing_tactics validation described in MARKETING_TACTICS_VALIDATION.md.
> Follow its rules exactly, take the next 100 units after the cursor, research and fix them 5 at
> a time, then update the cursor, the unit count and the log in that file.

## Log

### Session 1 — 2026-10-22

| Year | Movie | Result |
|---|---|---|
| 2026 | Baby Girl | 2 rows were about *Babygirl* (Kidman) → deleted; added trailer (20 Jan 2026) |
| 2026 | Furioso | 10 rows were about *Furiosa* → deleted; no sources for the German film |
| 2026 | Hoppers | D23 2024 row rewritten; added teaser (16 Jul 2025) |
| 2026 | J.G. Ballard Documentary \| The Unlimited Dream Company | Venice 2023 claim unsupported (film is a 1983 short) → deleted |
| 2026 | Jockey | Cannes 2024 claim was wrong film → deleted; added teaser (3 Jan 2026, Chennai) |
| 2026 | KD – The Devil | teaser rewritten (20 Oct 2022 Orion Mall); added trailer |
| 2026 | KD: The Devil | same as above |
| 2026 | Migration | 8 rows were about Illumination's *Migration* → deleted; no sources for the Iranian film |
| 2026 | Passages | orphan wrong-year copy of the 2023 film → deleted |
| 2026 | Puella Magi Madoka Magica the Movie -Walpurgisnacht: Rising- | teaser rewritten (25 Apr 2021); added trailers 2023-2026 |
| 2026 | Room | orphan wrong-year copy of the 2015 film → deleted |
| 2026 | Star Wars: The Mandalorian and Grogu (+ ", The Mandalorian and Grogu") | D23 row rewritten (Aug 2024 footage, SWC Japan Apr 2025); added teaser (Sep 2025), Super Bowl ad, trailer (17 Feb 2026) |
| 2026 | The Raja Saab (+ ", The RajaSaab") | glimpse/teaser rewritten (16 Jun 2025); added trailer (29 Sep 2025), 'Rebel Saab' single |
| 2026 | The Rajasaab | same as above |
| 2026 | The Wild Robot | 5 unsourced rows deleted; trailer (5 Mar 2024, Annecy 11 Jun 2024) and TIFF premiere rewritten |
| 2026 | Toxic | both teaser rows rewritten (title reveal Dec 2023 → teaser 20 Feb 2026) |
| 2025 | "Given The Movie: To the Sea" Live Performance | unsourced trailer → deleted |
| 2025 | (500) Days of Summer | 5 generic rows deleted; teaser + Sundance 17 Jan 2009 rewritten |
| 2025 | 21.37 | rows invented an athletics film (it's a John Paul II doc) → deleted |
| 2025 | 25 Jahre NightWash | no sources → deleted |
| 2025 | 31 Minutos: Calurosa Navidad \| Making Of | circular BTS row → deleted |
| 2025 | A Complete Unknown | teaser rewritten (24 Jul 2024); unsourced trailer row deleted |
| 2025 | A Minecraft Movie | teaser (4 Sep 2024) + trailer (19 Nov 2024) rewritten; meme rows deleted |
| 2025 | A Real Pain | premiere corrected TIFF → Sundance 2024; trailer (25 Sep 2024); junket deleted |
| 2025 | Aalambana | horror-teaser rows wrong (fantasy comedy) → replaced by trailer row |
| 2025 | Afraid? / Afters / Ahmed Sylla : Origami | unverifiable / wrong distributor → deleted |
| 2025 | Ainda Aqui / Amaran / Arrival | source rows are different films than the rows described → deleted |
| 2025 | Alien - Earth | CinemaCon claims wrong → replaced with Disney+ anniversary teaser |
| 2025 | Andor: Temporada 2 | D23 2024 row rewritten |
| 2025 | Avatar: Fire and Ash | D23 title-reveal rows rewritten (9 Aug 2024) |
| 2025 | BTS MAP OF THE SOUL ON:E | no film-specific trailer → deleted |
| 2025 | Babygirl | Venice row rewritten (30 Aug 2024); unsourced trailers deleted |
| 2025 | Back in Action | added teaser (14 Nov 2024); trailer (14 Jan 2025) |
| 2025 | Ballerina | teaser/trailer rewritten (26 Sep 2024) |
| 2025 | Bambi: The Reckoning | teaser (Apr 2024) + trailer (1 Apr 2025); meme row deleted |
| 2025 | Betelgeuse / Bir Ayrılığın Anatomisi / Blbec k večeři | wrong film (Beetlejuice / Anatomy of a Fall) or unverifiable → deleted |
| 2025 | Captain America: Brave New World | teaser, D23 + D23 Brazil trailer rewritten; TV spots deleted |
| 2025 | Chainsaw Man - The Movie: Reze Arc | teaser = Jump Festa announcement; added trailer |
| 2025 | Charlotte Film Festival Shorts (#1, #6) | festival rows reworded to fact |
| 2025 | Chhaava | teaser (15/19 Aug 2024) rewritten; added trailer (22 Jan 2025) |
| 2025 | Cięcie | Venice claim unsupported (15-min short) → deleted |
| 2025 | Companion | teaser (2 Oct 2024) kept; fa rows + unsourced rows deleted |
| 2025 | Conclave | premiere corrected Venice → Telluride; trailer 18 Jul 2024; 3 rows deleted |
| 2025 | Coolie | title teaser rewritten; added singles + audio launch (2 Aug 2025) |
| 2025 | Creature Commandos | D23/Feige row wrong → SDCC 2024 teaser |
| 2025 | Dansons sur le plancher rouge | invented Cannes claim → deleted |
| 2025 | Daredevil: Born Again | SDCC row wrong → D23 2024 footage; trailer Jan 2025 |
| 2025 | De Drill / De Vrienden van Amstel LIVE / Dear Evan Hansen (sv stage show) | unverifiable or wrong subject → deleted |
| 2025 | Demon Slayer: … Infinity Castle (2 units) | teaser row rewritten (30 Jun 2024 announcement) |
| 2025 | Dexter: Resurrection | SDCC 2024 row rewritten |
| 2025 | Diversion / EMILIA PÉREZ es una joya camp | wrong film / a YouTube video → deleted |
| 2025 | Don 3 | announcement teaser rewritten (9 Aug 2023) |
| 2025 | Doraemon … Nobita's Earth Symphony | 7 generic rows deleted; trailer + Vaundy theme rewritten |
| 2025 | Eden | Ron Howard rows on wrong films → deleted |
| 2025 | Elio | teasers (Jun 2023, Nov 2024); D23 corrected to 2022 |
| 2025 | Epic: The Musical | saga-release row rewritten |
| 2025 | F1 / F1: The Movie / F1: The Movie, F1 / Formula One | teaser (7 Jul 2024), APXGP stunt, haptic trailer (Jun 2025); BTS/placement rows deleted |
| 2025 | Fantastic Four | SDCC 2024 row rewritten (animatic, Fantasticar) |
| 2025 | Fighter | orphan wrong-year (2024 film) → deleted |
| 2025 | Flight Risk | trailer corrected Apple → Lionsgate (27 Jun 2024) |
| 2025 | Flow | Cannes UCR row confirmed + rewritten; trailer rewritten; teaser deleted |
| 2025 | Freakier Friday / Freaky Tales | D23 2024 and Sundance (Premieres, not Midnight) rows rewritten |
| 2025 | Game Changer | teaser corrected (Lucknow 9 Nov 2024, not Lyca); singles rewritten |
| 2025 | Gekijouban Project Sekai | 7 generic rows deleted; trailer + theme songs rewritten |
| 2025 | Gen Hoshino presents “Reassembly” | teaser row tied to the 'Gen' box set teaser |
| 2025 | Ghaati | glimpse (7 Nov 2024) rewritten; added trailer (11 Aug 2025) |
| 2025 | Ghostlight | premiere corrected SXSW → Sundance 2024 |
| 2025 | HYDE［INSIDE］LIVE 2024 -EXTRA- | generic → deleted |
| 2025 | Haralds Sīmanis. Dziesminieks un jumiķis | trailer row rewritten (2 Aug 2025) |
| 2025 | Hari Hara Veera Mallu (3 units) | unverified glimpse rows replaced with 'Maata Vinaali' single |
| 2025 | Here / Holdsworth / Hugo | wrong films → deleted |
| 2025 | How to Train Your Dragon | teaser rewritten (19 Nov 2024) |
| 2025 | Hypnosis Mic: Division Rap Battle Movie | 5 generic rows deleted; trailer (interactive 48 routes), announcement event, preview screening added |
| 2025 | IDOLiSH7 First BEAT! Movie Edition Part 1 | no sourced tactics → deleted |
| 2025 | INI THE MOVIE『I Need I』 | 8 generic rows deleted; trailer (8 Sep 2025) + theme song kept |
| 2025 | Ich will alles! Hildegard Knef | rows described a drama → replaced with Berlinale Panorama premiere |
| 2025 | Idli Kadai | audio launch rewritten (14 Sep 2025); singles added |
| 2025 | Inoxtag, l'ascension du Mont-Blanc / Inside the Worlds of Epic Universe | unverifiable / Universal at D23 → deleted |

Session 1 totals: table went from 201,829 rows / 69,989 units to 201,637 rows / 69,952 units;
138 rows written or rewritten from sources. Of the 100 units, most had at least one wrong or
unsourced row, and about a third of the units described a different film entirely.

### Session 2 — 2026-10-23

| Year | Movie | Result |
|---|---|---|
| 2025 | JOKER 2 2025 / Joker | rows described *Joker* (2019) / *Folie à Deux* (2024), not the 2025 entries → deleted |
| 2025 | Jesteśmy Żugajkami / João Chaves: a cidade, o poeta e o mar | no sources found → deleted |
| 2025 | Josiane Balasko, un grand cri d'amour ! | Éric Bitoun TV doc; premiere/TV/print rows unsourced → deleted |
| 2025 | K3 Originals: Het verhaal van Karen, Kristel & Kathleen | Videoland docuseries (6 Nov 2025); trailer/premiere/TV rows unsourced → deleted |
| 2025 | Kabaret Neo-Nówka - Tradycje Polskie ! | no sources → deleted |
| 2025 | Kannappa | teaser rewritten (Cannes 20 May 2024, online 14 Jun 2024); added trailer (14 Jun 2025) |
| 2025 | Kantara - A Legend: Chapter 1 / Kantara: A Legend - Chapter 1 | first-look teaser rewritten (27 Nov 2023); added trailer (22 Sep 2025) |
| 2025 | Kapr | 19-min Zlín student short; trailer row unsourced → deleted; added festival row (Canberra SFF premiere, Selb 2026) |
| 2025 | Koshi Inaba LIVE 2024 〜en-Zepp 1〜 | Blu-ray release, no theatrical trailer → deleted |
| 2025 | Kraven the Hunter | teaser/TV rows deleted; trailers (19 Jun 2023, Aug 2024) + CinemaCon Apr 2023 |
| 2025 | Kuberaa | teaser rewritten (glimpse 15 Nov 2024, teaser 25 May 2025) |
| 2025 | LA BREVEDAD - Bla Bla Bla Cliché (Video Oficial) | a music video itself; circular row → deleted |
| 2025 | La Compagnie | no sources → deleted |
| 2025 | La leggenda di Zagor | rows described a Zagor comic adaptation; it's a doc on musician Mirko Bertuccioli → deleted; added Pesaro FF premiere (16 Jun 2025) |
| 2025 | Le goût des choses | orphan wrong-year copy of *The Taste of Things* (2023 unit exists) → deleted |
| 2025 | Les Bodin's en folie (+ ": la suite") | M6 TV special (27 Dec 2025); trailer/TV rows unsourced → deleted |
| 2025 | Les commentaires de McFly et Carlito | YouTube content, no film campaign found → deleted |
| 2025 | Les dernières grandes transhumances | 68-min doc (4 Jun 2025), rows invented a comedy → deleted |
| 2025 | Little Simz: Amazon Music Songline / Louis-José Houde: Tu n'es pas spécial | no sources for the tactics → deleted |
| 2025 | Mafia: The Old Country | (a video game) Gamescom ONL reveal teaser 20 Aug 2024 rows rewritten |
| 2025 | Mahavatar Narsimha | teaser row (wrong studio) deleted; added trailer (9-10 Jul 2025) |
| 2025 | Maria | rows were Larraín's *Maria* (2024 unit exists); 2025 entries are other films → deleted |
| 2025 | Marvel Studios' The Fantastic Four: First Steps - World Premiere / Mein Minecraft Film | premiere video / not *A Minecraft Movie* (German title is *Ein Minecraft Film*) → deleted |
| 2025 | Mickey 17 | teaser (Dec 2022), trailers (18 Sep 2024, 22 Jan 2025) rewritten |
| 2025 | Minecraft: The Movie | trailer row rewritten (teaser 4 Sep 2024, trailer 19 Nov 2024) |
| 2025 | Mission: Impossible - The Final Reckoning | teaser (11 Nov 2024) rewritten; BTS deleted; TV row → Super Bowl LIX spot; added Cannes premiere (14 May 2025) |
| 2025 | Městečko Palermo | Brno student short; 6 invented rows deleted; added Donio crowdfunding |
| 2025 | NEW GENERATION THE LIVE ウルトラマンアーク編 | stage show, no sources → deleted |
| 2025 | Ne Zha 2 | teaser deleted; trailer row → A24 English dub trailer (Jul 2025) + IMAX trailer |
| 2025 | Ne Zha 2, Nezha: Mo tong nao hai | teaser deleted; A24 dub trailer row added |
| 2025 | Neujahrskonzert der Wiener Philharmoniker 2025 / NiziU Live With U 2024-2025 "Awake" / No Soy Tuyo | TV broadcast / Blu-ray only / no sources → deleted |
| 2025 | Niesamowite przygody skarpetek 2. Skarpetki górą! | trailer/TV rows deleted; added Helios Wrocław preview screenings (6-7 Sep 2025) |
| 2025 | Nosferatu | teaser corrected to 24 Jun 2024 (not late 2023); full trailer 30 Sep 2024 |
| 2025 | Odela 2 | "glimpse" → teaser launched at Maha Kumbh, Prayagraj (22 Feb 2025) |
| 2025 | Oh. What. Fun. | trailer row rewritten (6 Nov 2025) |
| 2025 | Open Marriage: Aru Fuufu No Taikenki | no sources → deleted |
| 2025 | Oppenheimer, la véritable histoire de la bombe atomique. | French doc; 13 rows were about Nolan's *Oppenheimer* → deleted |
| 2025 | Opération séduction / Potmě jsou všechny kočky černé / ROH Live 2024/25: Swan Lake | no sources → deleted |
| 2025 | Paddington in Peru | trailer row rewritten (12 Jun 2024, Aug 2024) |
| 2025 | Presence | Sundance premiere corrected 2025 → Jan 2024 |
| 2025 | Rómula | Colombian film; row credited Amenábar/Sitges (wrong film) → deleted |
| 2025 | SANS EUX / SPYAIR Just Like This 2024 / SURVEILLANCE: A BACKROOMS MOVIE | invented cast or no sources → deleted |
| 2025 | Sakamoto Days | (TV anime) teaser row → Jump Festa 2025 trailer/promo stage |
| 2025 | Schwarze Saue | invented "77th Berlinale competition" → deleted |
| 2025 | Seishun Inoshishi ATARASHII GAKKO! THE MOVIE | teaser → 30-sec trailer with 6 Jun 2025 date announcement |
| 2025 | Silvio Santos Vem Aí | trailer rewritten (28 Aug 2025, Paris Filmes); TV row deleted |
| 2025 | Sinners | teaser rows → first trailer 24 Sep 2024 |
| 2025 | Sky Force | teaser (2 Oct 2024), trailer (Jan 2025 Mumbai) rewritten; BTS/TV deleted |
| 2025 | Snow White | teaser corrected Dec 2023 → D23 9-10 Aug 2024; D23 rows rewritten; full trailer Dec 2024; TV deleted |
| 2025 | So oder so ist das Leben – Hildegard Knef / Soldier of Orange: The Musical / Srnky | no film campaign found (Srnky rows also credited the wrong director) → deleted |
| 2025 | Sonic the Hedgehog 3 | trailers rewritten (27 Aug 2024; 25 Nov 2024 via Sega Genesis cart) |
| 2025 | Stall-Erik and the Snapphans / THE RAMPAGE LIMITED LIVE 2024 | unfinished amateur project / concert film, no sources → deleted |
| 2025 | TRON: Ares | D23 Aug 2024 footage rows rewritten (no online teaser then); first trailer Apr 2025 |
| 2025 | Talk to Me | Polish 2025 entry; rows were the 2023 A24 film → deleted |
| 2025 | Thandel | glimpse rewritten ("Essence of Thandel", 6 Jan 2024); added Vizag trailer launch |
| 2025 | Thank God: Christmas at Keller Ranch / The 12th Annual On Cinema Oscar Special / The 31st Annual Screen Actors Guild Awards | TV movie / livestream / awards show; no sources → deleted |
| 2025 | The Amateur | trailer studio corrected Warner/Sony → 20th Century Studios (13 Nov 2024) |
| 2025 | The Beast | row claimed Bonello's film premiered at Cannes 2024 (it was Venice 2023; 2024 French entry) → deleted |
| 2025 | The Birdwatcher | TIFF 2024 claim unsourced → deleted |
| 2025 | The Brutalist | trailers (22 Oct 2024, Dec 2024) and Venice premiere (1 Sep 2024) rewritten |
| 2025 | The Creator | Argentine 2025 entry; rows were Gareth Edwards' 2023 film → deleted |
| 2025 | The Day the Earth Blew Up: A Looney Tunes Movie | CinemaCon row wrong → Annecy premiere (11 Jun 2024); trailer rewritten (Ketchup, Dec 2024 / 18 Feb 2025) |
| 2025 | The Electric State | teaser named the wrong actress → deleted; trailers reworded; added NYCC Oct 2024 panel |
| 2025 | The Fantastic 4: First Steps / The Fantastic Four: First Steps | D23/SDCC cast-reveal rows wrong → SDCC 27 Jul 2024 Hall H; added teaser (4 Feb 2025 Huntsville countdown) + CinemaCon trailer |
| 2025 | The Girl with the Needle | Cannes 2024 competition row confirmed + reworded |
| 2025 | The Life of Chuck | TIFF 2024 rows rewritten (6 Sep 2024, People's Choice Award) |
| 2025 | The Monkey | teaser distributor corrected WB → Neon (Aug 2024) |
| 2025 | The Raja Saab | orphan wrong-year copy of the Jan 2026 film (2026 units exist) → deleted |
| 2025 | The Rose of Versailles | teaser row → teaser #2 (2 Jul 2024) + 31 Jan 2025 date announcement |
| 2025 | The Surfer | Cannes 2024 Midnight Screenings premiere reworded |
| 2025 | The Veil / The Watchers | rows were the 2024 FX series / Shyamalan's 2024 film, not the 2025 entries → deleted |
| 2025 | They Call Him OG | "Hungry Cheetah" glimpse dated (2-3 Sep 2023); added trailer + OG Concert pre-release (21 Sep 2025) |
| 2025 | Thug Life | teaser → title video (6 Nov 2023); added trailer (17 May 2025) + audio launch (24 May 2025, postponed from 16 May) |
| 2025 | Thunderbolts* | SDCC claim deleted → D23 Aug 2024 + D23 Brazil Nov 2024; teaser Sep 2024; TV row → Super Bowl LIX spot |
| 2025 | Tutto il resto è noia / Uyku | no sources (Uyku's Cannes UCR claim unsupported) → deleted |
| 2025 | Valluvan | 2026 release, no teaser found → deleted |
| 2025 | Vidaamuyarchi | glimpse → teaser (28 Nov 2024); added trailer (16 Jan 2025) |
| 2025 | Vishwambara | teaser named Prabhas → corrected to Chiranjeevi, 12 Oct 2024 |
| 2025 | Vrusshabha | invented lead "Vrushanka" → Mohanlal teaser (18 Sep 2025); added Dec 2025 trailer |
| 2025 | Wallace & Gromit: Vengeance Most Fowl | teaser rewritten (16 Oct 2024; Aardman/BBC/Netflix) |

Session 2 totals: table went from 201,637 rows / 67,992 units to 201,464 rows / 67,940 units
(the unit count had already dropped from session 1's 69,952 before this session started); 106 rows
written or rewritten from sources. 52 of the 100 units ended with no rows: wrong film, wrong year,
or nothing sourced. Common errors: the wrong studio or distributor (*The Amateur*, *The Monkey*,
*Mahavatar Narsimha*), the wrong lead actor (*Vishwambara*, *Vrusshabha*), and festivals that
never happened (*Schwarze Saue* at the "77th Berlinale", *Uyku* at Cannes).

Language fix (same day, after the rule above was added): the 35 units of this batch that had
`unknown` rows were relabelled — 36 rows moved to the real language, 26 duplicate `unknown`
rows dropped. Session 1's 40 units with `unknown` rows were then relabelled the same way: 54 rows
moved, 21 duplicates dropped. After both passes, none of the 200 validated units has `unknown` rows.

Language follow-up (same day, per-language rule above), applied to all 200 validated units:
- **Full names:** `lv` → `latvian` (Flow, Haralds Sīmanis), `da` → `danish`, `cs` → `czech`,
  `pl` → `polish`, and Toxic's duplicate `Kannada` row merged into `kannada`.
- **Wrong market labels:** Ghostlight `th`, Dexter: Resurrection `tr` and Andor: Temporada 2
  `spanish` → `english`; Baby Girl 2026 `telugu` → `malayalam` (its row describes the Malayalam
  Nivin Pauly film).
- **Moved to the targeted audience:** Ne Zha 2 (both units) A24 dub trailer `chinese` →
  `english`; Mahavatar Narsimha trailer `telugu` → `hindi` (Hindi trailer, Vrindavan launch).
- **Per-language rows added:**
  - Kantara Ch. 1: the 7-language teaser plus per-star trailer launches for Hindi, Telugu,
    Tamil and Malayalam.
  - Teasers in five languages: Kannappa, KD – The Devil (by narrator), Toxic, Ghaati.
  - Jockey Telugu teaser.
  - Hari Hara Veera Mallu: "Maata Vinaali" in Tamil, Malayalam, Kannada and Hindi.
  - Separate trailers: Thandel Tamil (Karthi) and Hindi (Aamir Khan); Thug Life Hindi and
    Telugu; Chhaava Telugu dub.
  - Singles or soundtrack in several languages: Mahavatar Narsimha "Roar of Narsimha" (5
    languages); Vrusshabha soundtrack in Malayalam, Telugu, Kannada and Hindi, plus its Kochi
    trailer launch.
- **Not yet sourced per language** (left as is): Kuberaa, Game Changer, Coolie, The Raja Saab,
  They Call Him OG, Odela 2, Vidaamuyarchi, Star Wars: The Mandalorian and Grogu (Hindi).

Table after the follow-up: 201,483 rows, 7,795 `unknown` rows (all in unvalidated units).

Table-wide language rename (same day): every ISO code in `movie_marketing_tactics` and
`movie_marketing_tactics_scan_status` was converted to its full lowercase name. TMDB conventions
were followed: `cn` → `cantonese`, `xx` → `no language`. 16,336 tactic rows were moved; 25 of
them collided with an existing full-name row for the same film and tactic, and the full-name row
was kept. Table now 201,458 rows across 114 language names. Pre-rename copies are in
`movie_marketing_tactics_backup_20261023` and `movie_marketing_tactics_scan_status_backup_20261023`.
The scan (`MarketingTacticsDatabaseService.getCandidates`) now converts source languages with the
same mapping (`LanguageNames`), so new rows arrive with full names. Then `oriya` was merged into
`odia` (1,420 rows) and `rajastani` into `rajasthani` (49), with no collisions; 113 language names
remain.

Scan protection and consumers (same day):
- `movie_marketing_tactics_validated` was created and loaded with the 200 units from sessions 1
  and 2, recovered from the scan-status rows. All 100 session-2 units were matched, including
  the 87 units across both sessions that were left with no rows. The scan now skips these units.
- The SQL function `language_full_name(text)` is recreated by
  `MarketingTacticsDatabaseService.ensureSchema()` from `LanguageNames`. It is used by:
  - AuraService `MovieMarketingTacticsQueryServiceImpl` (peer-tactics join), which now also
    matches comma-list and coded languages;
  - AuraMath `data_entry_ui.py`, which lists tactics per language version with an "Audience
    language" choice when adding one.

package org.medilens.core;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.medilens.core.Domain.*;

/**
 * Conservative open-set medicine resolver.
 *
 * The resolver intentionally prefers "unresolved" over guessing.
 *
 * Improvements over the previous version:
 *  - tolerates common OCR punctuation/spacing changes
 *  - tolerates very small OCR spelling errors in long medicine words
 *  - handles PARACETAMOL / ACETAMINOPHEN and GTN / NITROGLYCERIN synonyms
 *  - does not fail just because the package contains another unrelated number
 *  - still requires brand + ingredient(s) + strength(s)
 *  - rejects conflicting known ingredients
 *  - rejects ambiguous matches instead of selecting one arbitrarily
 */
public final class Resolver {

    private final List<Medicine> catalog;

    /**
     * Strengths we currently use for medicine identity.
     *
     * Examples:
     *   650 MG
     *   0.4 MG
     *   25 MCG
     *   1 G
     *
     * OCR occasionally writes µg/ug instead of mcg, so those are normalized.
     */
    private static final Pattern STRENGTH = Pattern.compile(
            "(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*(MG|MCG|UG|µG|G)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NUMERIC_TOKEN =
            Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final Map<String, List<String>> INGREDIENT_SYNONYMS =
            new HashMap<>();

    static {
        INGREDIENT_SYNONYMS.put(
                "acetaminophen",
                Arrays.asList(
                        "ACETAMINOPHEN",
                        "PARACETAMOL"
                )
        );

        INGREDIENT_SYNONYMS.put(
                "paracetamol",
                Arrays.asList(
                        "PARACETAMOL",
                        "ACETAMINOPHEN"
                )
        );

        INGREDIENT_SYNONYMS.put(
                "nitroglycerin",
                Arrays.asList(
                        "NITROGLYCERIN",
                        "GLYCERYL TRINITRATE",
                        "GLYCEROL TRINITRATE",
                        "GTN"
                )
        );

        INGREDIENT_SYNONYMS.put(
                "glyceryl trinitrate",
                Arrays.asList(
                        "GLYCERYL TRINITRATE",
                        "GLYCEROL TRINITRATE",
                        "NITROGLYCERIN",
                        "GTN"
                )
        );
    }

    public Resolver(List<Medicine> catalog) {
        this.catalog = new ArrayList<>(catalog);
    }

    /**
     * Stable OCR normalization used by resolver/tests.
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }

        String text = Normalizer.normalize(
                value,
                Normalizer.Form.NFKC
        ).toUpperCase(Locale.ROOT);

        /*
         * Decimal commas are commonly printed/recognized as:
         *   0,5 MG
         * and should become:
         *   0.5 MG
         */
        text = text.replaceAll(
                "(?<=\\d),(?=\\d)",
                "."
        );

        /*
         * Normalize microgram spelling before punctuation is stripped.
         */
        text = text
                .replace("ΜG", "MCG")   // Greek capital mu in some OCR output
                .replace("µG", "MCG")
                .replace("UG", "MCG");

        /*
         * Hyphens/slashes/punctuation become spaces.
         * Decimal points are preserved.
         */
        text = text.replaceAll(
                "[^A-Z0-9.]+",
                " "
        );

        return text
                .trim()
                .replaceAll(
                        " +",
                        " "
                );
    }

    /**
     * Exact normalized phrase match.
     *
     * Kept public because existing tests/code may use it.
     */
    public static boolean phrase(
            String normalizedText,
            String phrase
    ) {
        String target = normalize(phrase);

        if (target.isEmpty()) {
            return false;
        }

        return (" " + normalizedText + " ")
                .contains(
                        " " + target + " "
                );
    }

    /**
     * Parse all dosage strengths visible in the OCR text.
     *
     * The old resolver required the observed set to equal the expected set.
     * That was too brittle because packaging may contain another dosage value,
     * e.g. another panel, comparison line, or nearby printed text.
     *
     * We now require all expected strengths to be present, while ambiguity is
     * handled by the rest of the resolver.
     */
    private static Set<String> strengths(String normalizedText) {
        Set<String> result = new HashSet<>();

        Matcher matcher = STRENGTH.matcher(normalizedText);

        while (matcher.find()) {
            String number = canonicalNumber(
                    matcher.group(1)
            );

            String unit = canonicalUnit(
                    matcher.group(2)
            );

            result.add(
                    number + unit
            );
        }

        return result;
    }

    private static String canonicalNumber(String value) {
        try {
            return new BigDecimal(value)
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (RuntimeException e) {
            return value;
        }
    }

    private static String canonicalUnit(String unit) {
        if (unit == null) {
            return "";
        }

        String value = unit
                .toUpperCase(Locale.ROOT)
                .replace("µ", "U");

        if (
                value.equals("UG") ||
                        value.equals("MCG")
        ) {
            return "MCG";
        }

        return value;
    }

    private static String ingredientStrength(
            Ingredient ingredient
    ) {
        return canonicalNumber(
                ingredient.strength
        ) + canonicalUnit(
                ingredient.unit
        );
    }

    /**
     * Detect an ingredient conservatively.
     *
     * Exact phrase matching is preferred.
     * Fuzzy matching is only allowed for sufficiently long medicine words and
     * only tolerates a very small edit distance.
     */
    private static boolean ingredientPresent(
            String normalizedText,
            Ingredient ingredient
    ) {
        List<String> aliases =
                INGREDIENT_SYNONYMS.get(
                        ingredient.id.toLowerCase(Locale.ROOT)
                );

        if (aliases != null) {
            for (String alias : aliases) {
                if (smartPhrase(normalizedText, alias)) {
                    return true;
                }
            }
        }

        return smartPhrase(
                normalizedText,
                ingredient.name
        );
    }

    /**
     * Brand/alias matching.
     *
     * We permit slight OCR spelling mistakes only when the alias contains
     * enough alphabetic content to make fuzzy matching safe.
     *
     * Short aliases such as "GTN", "IP", etc. remain exact-only.
     */
    private static boolean aliasPresent(
            String normalizedText,
            String alias
    ) {
        String normalizedAlias = normalize(alias);

        if (normalizedAlias.isEmpty()) {
            return false;
        }

        if (phrase(normalizedText, normalizedAlias)) {
            return true;
        }

        int letters = 0;

        for (int i = 0; i < normalizedAlias.length(); i++) {
            if (Character.isLetter(normalizedAlias.charAt(i))) {
                letters++;
            }
        }

        if (letters < 5) {
            return false;
        }

        return fuzzyPhrase(
                normalizedText,
                normalizedAlias
        );
    }

    /**
     * Strict for short phrases; slightly OCR-tolerant for longer medicine
     * names/ingredients.
     */
    private static boolean smartPhrase(
            String normalizedText,
            String phrase
    ) {
        String normalizedPhrase = normalize(phrase);

        if (normalizedPhrase.isEmpty()) {
            return false;
        }

        if (
                Resolver.phrase(
                        normalizedText,
                        normalizedPhrase
                )
        ) {
            return true;
        }

        int letters = 0;

        for (
                int i = 0;
                i < normalizedPhrase.length();
                i++
        ) {
            if (
                    Character.isLetter(
                            normalizedPhrase.charAt(i)
                    )
            ) {
                letters++;
            }
        }

        if (letters < 6) {
            return false;
        }

        return fuzzyPhrase(
                normalizedText,
                normalizedPhrase
        );
    }

    /**
     * Sliding token-window comparison.
     *
     * Numbers must match exactly.
     * Short words must match exactly.
     * Long words may tolerate only a very small Levenshtein distance.
     *
     * This catches OCR results such as:
     *   PARACETAM0L -> PARACETAMOL
     *   SILDENAF1L  -> SILDENAFIL
     *
     * without turning this into loose guessing.
     */
    private static boolean fuzzyPhrase(
            String normalizedText,
            String normalizedPhrase
    ) {
        String[] textTokens =
                splitTokens(normalizedText);

        String[] phraseTokens =
                splitTokens(normalizedPhrase);

        if (
                phraseTokens.length == 0 ||
                        textTokens.length < phraseTokens.length
        ) {
            return false;
        }

        for (
                int start = 0;
                start <= textTokens.length - phraseTokens.length;
                start++
        ) {
            boolean all = true;

            for (
                    int i = 0;
                    i < phraseTokens.length;
                    i++
            ) {
                if (
                        !tokenEquivalent(
                                textTokens[start + i],
                                phraseTokens[i]
                        )
                ) {
                    all = false;
                    break;
                }
            }

            if (all) {
                return true;
            }
        }

        return false;
    }

    private static String[] splitTokens(
            String value
    ) {
        String normalized = normalize(value);

        if (normalized.isEmpty()) {
            return new String[0];
        }

        return normalized.split(" ");
    }

    private static boolean tokenEquivalent(
            String observed,
            String expected
    ) {
        if (
                observed.equals(expected)
        ) {
            return true;
        }

        /*
         * Numbers are never fuzzy. 50 MG must not become 500 MG.
         */
        if (
                NUMERIC_TOKEN.matcher(observed).matches() ||
                        NUMERIC_TOKEN.matcher(expected).matches()
        ) {
            return false;
        }

        /*
         * Short tokens are too dangerous for fuzzy matching.
         */
        int minLength =
                Math.min(
                        observed.length(),
                        expected.length()
                );

        if (minLength < 5) {
            return false;
        }

        /*
         * Common OCR confusions.
         *
         * We compare both raw and confusion-normalized versions, but still
         * enforce a very small edit-distance threshold.
         */
        String a =
                normalizeOcrConfusions(observed);

        String b =
                normalizeOcrConfusions(expected);

        if (a.equals(b)) {
            return true;
        }

        int maxDistance;

        if (
                Math.max(
                        a.length(),
                        b.length()
                ) >= 10
        ) {
            maxDistance = 2;
        } else {
            maxDistance = 1;
        }

        /*
         * Do not allow wildly different word lengths.
         */
        if (
                Math.abs(
                        a.length() -
                                b.length()
                ) > maxDistance
        ) {
            return false;
        }

        return levenshteinAtMost(
                a,
                b,
                maxDistance
        );
    }

    private static String normalizeOcrConfusions(
            String value
    ) {
        /*
         * Only used for comparing alphabetic medicine words.
         *
         * O/0 and I/1 are common OCR swaps.
         */
        return value
                .replace('0', 'O')
                .replace('1', 'I');
    }

    private static boolean levenshteinAtMost(
            String a,
            String b,
            int limit
    ) {
        if (
                Math.abs(
                        a.length() -
                                b.length()
                ) > limit
        ) {
            return false;
        }

        int[] previous =
                new int[b.length() + 1];

        int[] current =
                new int[b.length() + 1];

        for (
                int j = 0;
                j <= b.length();
                j++
        ) {
            previous[j] = j;
        }

        for (
                int i = 1;
                i <= a.length();
                i++
        ) {
            current[0] = i;

            int rowBest =
                    current[0];

            for (
                    int j = 1;
                    j <= b.length();
                    j++
            ) {
                int cost =
                        a.charAt(i - 1) ==
                                b.charAt(j - 1)
                                ? 0
                                : 1;

                current[j] =
                        Math.min(
                                Math.min(
                                        current[j - 1] + 1,
                                        previous[j] + 1
                                ),
                                previous[j - 1] + cost
                        );

                rowBest =
                        Math.min(
                                rowBest,
                                current[j]
                        );
            }

            if (rowBest > limit) {
                return false;
            }

            int[] temp =
                    previous;

            previous =
                    current;

            current =
                    temp;
        }

        return previous[b.length()] <= limit;
    }

    /**
     * True if the OCR text contains every expected ingredient strength.
     *
     * Extra unrelated strength text does not automatically invalidate the
     * package anymore.
     */
    private static boolean expectedStrengthsPresent(
            Set<String> observed,
            Medicine medicine
    ) {
        Set<String> expected =
                new HashSet<>();

        for (
                Ingredient ingredient :
                medicine.ingredients
        ) {
            expected.add(
                    ingredientStrength(
                            ingredient
                    )
            );
        }

        return observed.containsAll(
                expected
        );
    }

    /**
     * Reject a package if it visibly contains another catalog ingredient that
     * is not part of this formulation.
     *
     * This helps distinguish combination medicines from similarly branded
     * single-ingredient products.
     */
    private boolean containsConflictingIngredient(
            String normalizedText,
            Medicine medicine
    ) {
        Set<String> expectedIds =
                new HashSet<>();

        for (
                Ingredient ingredient :
                medicine.ingredients
        ) {
            expectedIds.add(
                    ingredient.id
                            .toLowerCase(Locale.ROOT)
            );
        }

        Set<String> checked =
                new HashSet<>();

        for (
                Medicine other :
                catalog
        ) {
            for (
                    Ingredient ingredient :
                    other.ingredients
            ) {
                String id =
                        ingredient.id
                                .toLowerCase(Locale.ROOT);

                if (
                        expectedIds.contains(id) ||
                                !checked.add(id)
                ) {
                    continue;
                }

                if (
                        ingredientPresent(
                                normalizedText,
                                ingredient
                        )
                ) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean allIngredientsPresent(
            String normalizedText,
            Medicine medicine
    ) {
        for (
                Ingredient ingredient :
                medicine.ingredients
        ) {
            if (
                    !ingredientPresent(
                            normalizedText,
                            ingredient
                    )
            ) {
                return false;
            }
        }

        return true;
    }

    private static boolean requiredTokensPresent(
            String normalizedText,
            Medicine medicine
    ) {
        for (
                String token :
                medicine.requiredTokens
        ) {
            if (
                    !smartPhrase(
                            normalizedText,
                            token
                    )
            ) {
                return false;
            }
        }

        return true;
    }

    private static boolean excludedTokenPresent(
            String normalizedText,
            Medicine medicine
    ) {
        for (
                String token :
                medicine.excludedTokens
        ) {
            /*
             * Exclusion is intentionally exact/conservative.
             * We do not want a fuzzy OCR typo to incorrectly exclude a valid
             * formulation.
             */
            if (
                    phrase(
                            normalizedText,
                            token
                    )
            ) {
                return true;
            }
        }

        return false;
    }

    private static boolean brandPresent(
            String normalizedText,
            Medicine medicine
    ) {
        for (
                String alias :
                medicine.aliases
        ) {
            if (
                    aliasPresent(
                            normalizedText,
                            alias
                    )
            ) {
                return true;
            }
        }

        return false;
    }

    /**
     * Main open-set resolve operation.
     *
     * A medicine is accepted only when:
     *
     *  1. one of its brand aliases is visible
     *  2. all required tokens are visible
     *  3. no explicit excluded token is visible
     *  4. all expected ingredients are visible
     *  5. all expected strengths are visible
     *  6. no conflicting known ingredient is visible
     *  7. exactly one catalog formulation satisfies all conditions
     *
     * The Tracker still performs repeated-frame confirmation after this.
     */
    public Match resolve(String raw) {
        String text =
                normalize(raw);

        if (
                text.length() < 4
        ) {
            return Match.unresolved(
                    "Move closer to the printed label"
            );
        }

        Set<String> observedStrengths =
                strengths(text);

        List<Medicine> brandMatches =
                new ArrayList<>();

        List<Medicine> valid =
                new ArrayList<>();

        for (
                Medicine medicine :
                catalog
        ) {
            if (
                    !brandPresent(
                            text,
                            medicine
                    )
            ) {
                continue;
            }

            brandMatches.add(
                    medicine
            );

            if (
                    excludedTokenPresent(
                            text,
                            medicine
                    )
            ) {
                continue;
            }

            if (
                    !requiredTokensPresent(
                            text,
                            medicine
                    )
            ) {
                continue;
            }

            if (
                    !allIngredientsPresent(
                            text,
                            medicine
                    )
            ) {
                continue;
            }

            if (
                    !expectedStrengthsPresent(
                            observedStrengths,
                            medicine
                    )
            ) {
                continue;
            }

            if (
                    containsConflictingIngredient(
                            text,
                            medicine
                    )
            ) {
                continue;
            }

            valid.add(
                    medicine
            );
        }

        /*
         * Multiple valid formulations means OCR is still ambiguous.
         * Never choose arbitrarily.
         */
        if (
                valid.size() > 1
        ) {
            return Match.unresolved(
                    "More than one formulation matches · keep one package centered"
            );
        }

        if (
                valid.size() == 1
        ) {
            return new Match(
                    valid.get(0),
                    Confidence.HIGH,
                    "Brand, ingredient and strength matched; awaiting repeated observation"
            );
        }

        /*
         * A brand matched, but the complete formulation did not.
         * This is useful feedback and is safer than guessing.
         */
        if (
                !brandMatches.isEmpty()
        ) {
            return Match.unresolved(
                    "Keep the brand, ingredient and strength visible together"
            );
        }

        return Match.unresolved(
                "No supported medicine identified"
        );
    }
}

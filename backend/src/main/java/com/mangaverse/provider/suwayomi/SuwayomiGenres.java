package com.mangaverse.provider.suwayomi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.mangaverse.common.Json;
import com.mangaverse.provider.spi.Genre;

import tools.jackson.databind.JsonNode;

/**
 * Acha o filtro de gêneros de uma fonte do Suwayomi. As extensões não têm uma lista padrão: cada uma expõe os gêneros
 * como um grupo de caixas ("Gêneros", "Gênero", "Tags"…) ou um seletor ("Gêneros: Indiferente, Ação, …").
 * O id de cada gênero é a posição da opção dentro desse filtro.
 */
final class SuwayomiGenres {

    private static final Pattern STRICT = Pattern.compile("g[eê]n[eê]ros?|genres?|categor", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOSE = Pattern.compile("\\btags?\\b|\\btemas?\\b|\\bthemes?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXCLUDE = Pattern.compile(
            "excl|ordem|order|sort|status|estado|tipo de|type|\\bano\\b|year|idioma|lang|demograf|formato|format|operador|modo|conte[uú]do|classifica",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_OPTION = Pattern.compile(
            "^\\s*(todos|todas|tudo|all|any|qualquer|indiferente|nenhum|none|selecione.*|select.*|escolha.*|<.*>|-+|\\*)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Filtro de gêneros encontrado: posição na lista de filtros, se é grupo e o tipo de cada opção do grupo. */
    record Found(int position, boolean group, List<Genre> genres, List<String> optionTypes) {
    }

    private SuwayomiGenres() {
    }

    static Found find(JsonNode filters) {
        List<Found> candidates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int i = 0;
        for (JsonNode f : filters == null || !filters.isArray() ? List.<JsonNode>of() : iterable(filters)) {
            Found found = options(i, f);
            if (found != null && found.genres().size() >= 3) {
                candidates.add(found);
                names.add(Json.text(f, "name") == null ? "" : Json.text(f, "name"));
            } else {
                candidates.add(null);
                names.add("");
            }
            i++;
        }
        Predicate<String> strict = n -> STRICT.matcher(n).find() && !EXCLUDE.matcher(n).find();
        Predicate<String> loose = n -> LOOSE.matcher(n).find() && !EXCLUDE.matcher(n).find();
        Found best = pick(candidates, names, true, strict);
        if (best == null) best = pick(candidates, names, false, strict);
        if (best == null) best = pick(candidates, names, true, loose);
        if (best == null) best = pick(candidates, names, false, loose);
        if (best == null) {
            // sem nome reconhecível: o maior grupo de caixas costuma ser o de gêneros
            best = pick(candidates, names, true, n -> !EXCLUDE.matcher(n).find());
            if (best != null && best.genres().size() < 8) best = null;
        }
        return best;
    }

    /** Alteração de filtro para o {@code fetchSourceManga} marcando só o gênero {@code index}. */
    static Map<String, Object> change(Found found, int index) {
        if (!found.group()) {
            return Map.of("position", found.position(), "selectState", index);
        }
        String type = index < found.optionTypes().size() ? found.optionTypes().get(index) : "CheckBoxFilter";
        Map<String, Object> child = "TriStateFilter".equals(type)
                ? Map.of("position", index, "triState", "INCLUDE")
                : Map.of("position", index, "checkBoxState", true);
        return Map.of("position", found.position(), "groupChange", child);
    }

    private static Found pick(List<Found> candidates, List<String> names, boolean group, Predicate<String> name) {
        Found best = null;
        for (int i = 0; i < candidates.size(); i++) {
            Found f = candidates.get(i);
            if (f != null && f.group() == group && name.test(names.get(i))
                    && (best == null || f.genres().size() > best.genres().size())) {
                best = f;
            }
        }
        return best;
    }

    private static Found options(int position, JsonNode f) {
        String type = Json.text(f, "__typename");
        List<Genre> genres = new ArrayList<>();
        List<String> types = new ArrayList<>();
        if ("GroupFilter".equals(type)) {
            int j = 0;
            for (JsonNode o : iterable(f.path("filters"))) {
                String t = Json.text(o, "__typename");
                types.add(t);
                String name = Json.text(o, "name");
                if (("CheckBoxFilter".equals(t) || "TriStateFilter".equals(t)) && name != null && !name.isBlank()) {
                    genres.add(new Genre(String.valueOf(j), name.trim()));
                }
                j++;
            }
            return new Found(position, true, genres, types);
        }
        if ("SelectFilter".equals(type)) {
            int j = 0;
            for (JsonNode v : iterable(f.path("values"))) {
                String name = v.isString() ? v.asString().trim() : "";
                if (!name.isEmpty() && !ALL_OPTION.matcher(name).matches()) {
                    genres.add(new Genre(String.valueOf(j), name));
                }
                j++;
            }
            return new Found(position, false, genres, types);
        }
        return null;
    }

    private static List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }

    static Comparator<Genre> byName() {
        return Comparator.comparing(g -> g.name().toLowerCase(java.util.Locale.ROOT));
    }
}

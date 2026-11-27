package com.mangaverse.provider.suwayomi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mangaverse.provider.spi.Genre;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Formatos reais devolvidos pelo Suwayomi v2.4.2366 (MangaDex pt-BR, Tia Manhwa, MR Tenzus, Manga Livre). */
class SuwayomiGenresTest {

    private static JsonNode json(String s) {
        return JsonMapper.builder().build().readTree(s.replace('\'', '"'));
    }

    @Test
    void prefersGenreGroupOverThemeAndContentGroups() {
        JsonNode filters = json("""
                [{'__typename':'CheckBoxFilter','name':null},
                 {'__typename':'GroupFilter','name':'Classificação de conteúdo','filters':[
                   {'__typename':'CheckBoxFilter','name':'Seguro'},{'__typename':'CheckBoxFilter','name':'Sugestivo'},{'__typename':'CheckBoxFilter','name':'Erótico'}]},
                 {'__typename':'SortFilter','name':'Ordenar'},
                 {'__typename':'GroupFilter','name':'Gênero','filters':[
                   {'__typename':'TriStateFilter','name':'Ação'},{'__typename':'TriStateFilter','name':'Aventura'},{'__typename':'TriStateFilter','name':'Comédia'}]},
                 {'__typename':'GroupFilter','name':'Tema','filters':[
                   {'__typename':'TriStateFilter','name':'Alienígenas'},{'__typename':'TriStateFilter','name':'Animais'},
                   {'__typename':'TriStateFilter','name':'Artes marciais'},{'__typename':'TriStateFilter','name':'Culinária'}]}]""");

        SuwayomiGenres.Found found = SuwayomiGenres.find(filters);

        assertThat(found.position()).isEqualTo(3);
        assertThat(found.genres()).extracting(Genre::name).containsExactly("Ação", "Aventura", "Comédia");
        assertThat(SuwayomiGenres.change(found, 1))
                .isEqualTo(Map.of("position", 3, "groupChange", Map.of("position", 1, "triState", "INCLUDE")));
    }

    @Test
    void selectFilterSkipsTheAnyOptionAndKeepsOriginalIndexes() {
        JsonNode filters = json("""
                [{'__typename':'SelectFilter','name':'Ordenar por','values':['Relevância','Recentes','A-Z','Avaliação']},
                 {'__typename':'SelectFilter','name':'Gêneros','values':['Indiferente','Abuso (9)','Ação (92)','Drama (40)']}]""");

        SuwayomiGenres.Found found = SuwayomiGenres.find(filters);

        assertThat(found.group()).isFalse();
        assertThat(found.genres()).containsExactly(new Genre("1", "Abuso (9)"), new Genre("2", "Ação (92)"), new Genre("3", "Drama (40)"));
        assertThat(SuwayomiGenres.change(found, 2)).isEqualTo(Map.of("position", 1, "selectState", 2));
    }

    @Test
    void checkBoxGroupUsesCheckBoxState() {
        JsonNode filters = json("""
                [{'__typename':'GroupFilter','name':'Estado','filters':[
                   {'__typename':'CheckBoxFilter','name':'Completo'},{'__typename':'CheckBoxFilter','name':'Em andamento'},{'__typename':'CheckBoxFilter','name':'Cancelado'}]},
                 {'__typename':'SelectFilter','name':'Operador dos gêneros','values':['OU','E']},
                 {'__typename':'GroupFilter','name':'Gêneros','filters':[
                   {'__typename':'CheckBoxFilter','name':'Adulto (98)'},{'__typename':'CheckBoxFilter','name':'Aventura (6)'},{'__typename':'CheckBoxFilter','name':'Drama'}]}]""");

        SuwayomiGenres.Found found = SuwayomiGenres.find(filters);

        assertThat(found.position()).isEqualTo(2);
        assertThat(SuwayomiGenres.change(found, 0))
                .isEqualTo(Map.of("position", 2, "groupChange", Map.of("position", 0, "checkBoxState", true)));
    }

    @Test
    void sourceWithoutGenreFilterHasNoGenres() {
        JsonNode filters = json("""
                [{'__typename':'TextFilter','name':'Autor'},
                 {'__typename':'GroupFilter','name':'Estado','filters':[
                   {'__typename':'CheckBoxFilter','name':'Completo'},{'__typename':'CheckBoxFilter','name':'Em andamento'},{'__typename':'CheckBoxFilter','name':'Cancelado'}]},
                 {'__typename':'SelectFilter','name':'Conteúdo adulto','values':['Indiferente','Nenhum','Somente']}]""");

        assertThat(SuwayomiGenres.find(filters)).isNull();
        assertThat(SuwayomiGenres.find(json("[]"))).isNull();
    }
}

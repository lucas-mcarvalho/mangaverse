package com.mangaverse.mobile.ext

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/** Um gênero/categoria de uma fonte: a posição da opção dentro do filtro de gêneros e o nome. */
data class Genre(val index: Int, val name: String)

/**
 * Categorias das fontes. As extensões não têm uma lista padrão de gêneros: cada uma expõe os seus nos filtros da busca
 * (um grupo de caixas "Gêneros", um seletor "Gênero"…). Aqui achamos esse filtro e montamos a busca por um gênero.
 */
object Genres {
    // "Gênero" vem antes de "Tema"/"Tags" (a MangaDex tem os dois). Nomes conferidos nas extensões do Keiyoushi.
    private val STRICT_NAME = Regex("g[eê]n[eê]ros?|genres?|categor", RegexOption.IGNORE_CASE)
    private val LOOSE_NAME = Regex("\\btags?\\b|\\btemas?\\b|\\bthemes?\\b", RegexOption.IGNORE_CASE)
    private val EXCLUDE_NAME = Regex(
        "excl|ordem|order|sort|status|estado|tipo de|type|\\bano\\b|year|idioma|lang|demograf|formato|format|operador|modo|conte[uú]do|classifica",
        RegexOption.IGNORE_CASE,
    )
    private val ALL_OPTION = Regex(
        "^\\s*(todos|todas|tudo|all|any|qualquer|indiferente|nenhum|none|selecione.*|select.*|escolha.*|<.*>|-+|\\*)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private data class Found(val filterIndex: Int, val genres: List<Genre>)

    private fun options(f: Filter<*>): List<Genre> = when (f) {
        is Filter.Group<*> -> f.state.mapIndexedNotNull { i, v ->
            when (v) {
                is Filter.CheckBox -> Genre(i, v.name)
                is Filter.TriState -> Genre(i, v.name)
                else -> null
            }
        }
        is Filter.Select<*> -> f.values.mapIndexedNotNull { i, v -> v.toString().trim().takeUnless { it.isEmpty() || ALL_OPTION.matches(it) }?.let { Genre(i, it) } }
        else -> emptyList()
    }.filter { it.name.isNotBlank() }

    private fun find(filters: FilterList): Found? {
        val list = filters.toList()
        fun pick(pred: (Filter<*>) -> Boolean): Found? = list.withIndex()
            .filter { (_, f) -> pred(f) }
            .map { (i, f) -> Found(i, options(f)) }
            .filter { it.genres.size >= 3 }
            .maxByOrNull { it.genres.size }

        val strict = { f: Filter<*> -> STRICT_NAME.containsMatchIn(f.name) && !EXCLUDE_NAME.containsMatchIn(f.name) }
        val loose = { f: Filter<*> -> LOOSE_NAME.containsMatchIn(f.name) && !EXCLUDE_NAME.containsMatchIn(f.name) }
        return pick { it is Filter.Group<*> && strict(it) }
            ?: pick { it is Filter.Select<*> && strict(it) }
            ?: pick { it is Filter.Group<*> && loose(it) }
            ?: pick { it is Filter.Select<*> && loose(it) }
            // sem nome reconhecível: o maior grupo de caixas costuma ser o de gêneros
            ?: pick { it is Filter.Group<*> && !EXCLUDE_NAME.containsMatchIn(it.name) && options(it).size >= 8 }
    }

    /** Gêneros da fonte (lista vazia se ela não tiver filtro de gênero). Pode acessar a rede em algumas fontes: chame fora da main. */
    fun of(source: CatalogueSource): List<Genre> = runCatching { find(source.getFilterList())?.genres }.getOrNull().orEmpty()

    /** Filtros da busca com só o gênero [index] marcado, ou `null` se a fonte não tiver esse gênero. */
    fun filtersFor(source: CatalogueSource, index: Int): Pair<Genre, FilterList>? {
        val filters = source.getFilterList()
        val found = find(filters) ?: return null
        val genre = found.genres.firstOrNull { it.index == index } ?: return null
        when (val f = filters[found.filterIndex]) {
            is Filter.Group<*> -> when (val v = f.state[index]) {
                is Filter.CheckBox -> v.state = true
                is Filter.TriState -> v.state = Filter.TriState.STATE_INCLUDE
                else -> return null
            }
            is Filter.Select<*> -> f.state = index
            else -> return null
        }
        return genre to filters
    }
}

package com.aistra.hail.utils

import com.aistra.hail.app.HailData

/** Combines Hail's three search algorithms (T9/fuzzy/pinyin) against one or more candidate strings. */
fun matchesSearchQuery(query: String, vararg names: String): Boolean =
    (HailData.nineKeySearch && NineKeySearch.search(query, *names)) ||
        names.any { FuzzySearch.search(it, query) } ||
        names.any { PinyinSearch.searchPinyinAll(it, query) }

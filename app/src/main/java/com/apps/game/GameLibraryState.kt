package com.apps.game

import com.yuki.yukihub.model.Game
import java.util.Collections
import java.util.Comparator
import kotlin.math.max
import kotlin.math.min

/** Mutable list/filter/paging state shared by portrait and Pad library surfaces. */
class GameLibraryState {
    fun interface Filter { fun matches(game: Game, query: String, category: String): Boolean }
    private val all = mutableListOf<Game>(); private val filtered = mutableListOf<Game>(); private val visible = mutableListOf<Game>()
    private var query = ""; private var category = ""; private var page = 0; private var fullyLoaded = false
    fun replaceAll(games: List<Game>?) { all.clear(); games?.let(all::addAll) }
    fun setQuery(value: String?) { query = value.orEmpty() }; fun setCategory(value: String?) { category = value.orEmpty() }
    fun getQuery() = query; fun getCategory() = category; fun getAll(): List<Game> = Collections.unmodifiableList(all); fun getFiltered(): List<Game> = Collections.unmodifiableList(filtered); fun getVisible(): List<Game> = Collections.unmodifiableList(visible); fun isFullyLoaded()=fullyLoaded; fun getPage()=page
    fun rebuild(filter: Filter?, titleOrder: Comparator<Game>?, pageSize: Int, horizontalPaging: Boolean) { filtered.clear(); all.filter { filter == null || filter.matches(it,query,category) }.let(filtered::addAll); if(category.trim().isEmpty() && titleOrder!=null) filtered.sortWith(titleOrder); page=0; visible.clear(); fullyLoaded=filtered.isEmpty(); if(horizontalPaging) renderPage(pageSize) else loadNext(pageSize) }
    fun nextPage(pageSize:Int):Boolean { if(page+1>=totalPages(pageSize)) return false; page++; renderPage(pageSize); return true }
    fun previousPage(pageSize:Int):Boolean { if(page<=0)return false; page--; renderPage(pageSize); return true }
    fun renderPage(pageSize:Int) { val size=max(1,pageSize); val total=totalPages(size); page=min(max(0,page),total-1); val start=page*size; val end=min(start+size,filtered.size); visible.clear(); if(start<end)visible.addAll(filtered.subList(start,end)); fullyLoaded=page>=total-1 }
    fun loadNext(pageSize:Int) { val end=min(visible.size+max(1,pageSize),filtered.size); if(visible.size<end)visible.addAll(filtered.subList(visible.size,end)); fullyLoaded=end>=filtered.size }
    fun updateGame(updated:Game?, filter:Filter?):Int { updated?:return -1; indexOf(all,updated.id).takeIf{it>=0}?.let{all[it]=updated}; val matches=filter?.matches(updated,query,category)?:false; val fi=indexOf(filtered,updated.id); val vi=indexOf(visible,updated.id); if(!matches){if(fi>=0)filtered.removeAt(fi);if(vi>=0)visible.removeAt(vi);fullyLoaded=visible.size>=filtered.size;return vi};if(fi>=0)filtered[fi]=updated else filtered.add(updated);if(vi>=0)visible[vi]=updated;return vi }
    fun removeGame(id:Long):Int { indexOf(all,id).takeIf{it>=0}?.let(all::removeAt); indexOf(filtered,id).takeIf{it>=0}?.let(filtered::removeAt); val vi=indexOf(visible,id);if(vi>=0)visible.removeAt(vi);fullyLoaded=visible.size>=filtered.size;return vi }
    private fun indexOf(list:List<Game>,id:Long)=list.indexOfFirst{it.id==id}; private fun totalPages(pageSize:Int)=max(1,(filtered.size+max(1,pageSize)-1)/max(1,pageSize))
}

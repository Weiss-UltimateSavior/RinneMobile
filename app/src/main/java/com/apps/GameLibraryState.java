package com.apps;

import com.yuki.yukihub.model.Game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Pure list/filter/paging state shared by portrait and Pad library surfaces. */
public final class GameLibraryState {
    public interface Filter { boolean matches(Game game, String query, String category); }

    private final List<Game> all = new ArrayList<>();
    private final List<Game> filtered = new ArrayList<>();
    private final List<Game> visible = new ArrayList<>();
    private String query = "";
    private String category = "";
    private int page;
    private boolean fullyLoaded;

    public void replaceAll(List<Game> games) { all.clear(); if (games != null) all.addAll(games); }
    public void setQuery(String value) { query = value == null ? "" : value; }
    public void setCategory(String value) { category = value == null ? "" : value; }
    public String getQuery() { return query; }
    public String getCategory() { return category; }
    public List<Game> getAll() { return Collections.unmodifiableList(all); }
    public List<Game> getFiltered() { return Collections.unmodifiableList(filtered); }
    public List<Game> getVisible() { return Collections.unmodifiableList(visible); }
    public boolean isFullyLoaded() { return fullyLoaded; }
    public int getPage() { return page; }

    public void rebuild(Filter filter, Comparator<Game> titleOrder, int pageSize, boolean horizontalPaging) {
        filtered.clear();
        for (Game game : all) if (game != null && (filter == null || filter.matches(game, query, category))) filtered.add(game);
        if (category.trim().isEmpty() && titleOrder != null) Collections.sort(filtered, titleOrder);
        page = 0;
        visible.clear();
        fullyLoaded = filtered.isEmpty();
        if (horizontalPaging) renderPage(pageSize); else loadNext(pageSize);
    }

    public boolean nextPage(int pageSize) {
        int total = totalPages(pageSize);
        if (page + 1 >= total) return false;
        page++;
        renderPage(pageSize);
        return true;
    }
    public boolean previousPage(int pageSize) {
        if (page <= 0) return false;
        page--;
        renderPage(pageSize);
        return true;
    }
    public void renderPage(int pageSize) {
        int size = Math.max(1, pageSize);
        int total = totalPages(size);
        page = Math.max(0, Math.min(page, total - 1));
        int start = page * size;
        int end = Math.min(start + size, filtered.size());
        visible.clear();
        if (start < end) visible.addAll(filtered.subList(start, end));
        fullyLoaded = page >= total - 1;
    }
    public void loadNext(int pageSize) {
        int end = Math.min(visible.size() + Math.max(1, pageSize), filtered.size());
        if (visible.size() < end) visible.addAll(filtered.subList(visible.size(), end));
        fullyLoaded = end >= filtered.size();
    }
    private int totalPages(int pageSize) { return Math.max(1, (filtered.size() + Math.max(1, pageSize) - 1) / Math.max(1, pageSize)); }
}

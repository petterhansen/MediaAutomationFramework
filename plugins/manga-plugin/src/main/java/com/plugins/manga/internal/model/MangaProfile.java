package com.plugins.manga.internal.model;

public record MangaProfile(String id, String name, String passwordHash, long createdAt) {
}

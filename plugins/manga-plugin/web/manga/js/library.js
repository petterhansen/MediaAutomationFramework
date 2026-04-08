/**
 * Library.js — Manga Library & Search Frontend
 */
(function () {
    'use strict';

    // ======================== STATE ========================
    let currentManga = null;     // Currently viewed manga details
    let searchTimeout = null;
    let providers = [];
    let currentProfile = null;

    const API_BASE = '/api/manga';

    // Auth token (shared with dashboard)
    function getToken() {
        return localStorage.getItem('maf_token') || '';
    }

    function apiHeaders() {
        const headers = {
            'X-MAF-Token': getToken()
        };
        const profileId = localStorage.getItem('maf_manga_profile_id');
        if (profileId) {
            headers['X-Manga-Profile-Id'] = profileId;
        }
        return headers;
    }

    async function fetchApi(url, options = {}) {
        if (!options.headers) options.headers = apiHeaders();
        const res = await fetch(url, options);
        if (res.status === 401 && !options.silent) {
            window.location.href = '/login?redirect=' + encodeURIComponent(window.location.pathname + window.location.search);
            throw new Error('Unauthorized');
        }
        return res;
    }

    /**
     * Standardizes image URLs:
     * 1. If it's an internal /manga/ path, inject the MAF token.
     * 2. If it's an external URL (mangadex, mangapill, etc.), wrap with our proxy.
     * 3. Prevents double-proxying.
     */
    function getProxiedUrl(url) {
        if (!url) return '';
        
        // 1. Internal library path (local files)
        if (url.startsWith('/manga/')) {
            return url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(getToken());
        }

        // 2. Already proxied -> return as-is
        if (url.includes('/api/manga/proxy-image')) {
            return url;
        }

        // 3. External URLs -> wrap with proxy
        if (url.startsWith('http') || url.includes('mangadex') || url.includes('mangapill')) {
            return API_BASE + '/proxy-image?url=' + encodeURIComponent(url);
        }

        return url;
    }

    // ======================== INIT ========================
    document.addEventListener('DOMContentLoaded', () => {
        loadProviders();
        checkProfile();
        setupSearch();
        setupNsfwToggle();
        loadPopular();

        // Handle auto-open for discovery redirects (e.g. from Genre page)
        const params = new URLSearchParams(window.location.search);
        const autoId = params.get('id');
        const autoProvider = params.get('provider');
        if (autoId && autoProvider) {
            setTimeout(() => {
                openMangaDetail(autoId, autoProvider);
                // Clear params to prevent re-opening on manual refresh
                window.history.replaceState({}, document.title, window.location.pathname);
            }, 500);
        }
    });

    async function loadPopular() {
        const grid = document.getElementById('popular-grid');
        grid.innerHTML = '<div class="loading-spinner"><div class="spinner"></div></div>';
        try {
            const res = await fetchApi(API_BASE + '/popular?limit=12', { silent: true });
            const manga = await res.json();
            grid.innerHTML = '';
            manga.forEach(m => grid.appendChild(createMangaCard(m, false)));
        } catch (e) {
            grid.innerHTML = '<p class="text-muted">Failed to load popular manga</p>';
        }
    }

    window.surpriseMe = async function() {
        const btn = document.getElementById('surprise-btn');
        const originalText = btn.innerHTML;
        btn.innerHTML = '🎲 Rolling...';
        btn.disabled = true;

        try {
            const res = await fetchApi(API_BASE + '/random?provider=mangapill');
            const manga = await res.json();
            if (manga && manga.id) {
                openMangaDetail(manga.id, manga.provider);
            } else {
                showToast('The dice failed! Try again.', 'error');
            }
        } catch (e) {
            showToast('Random discovery failed', 'error');
        } finally {
            btn.innerHTML = originalText;
            btn.disabled = false;
        }
    }

    function setupNsfwToggle() {
        const toggle = document.getElementById('nsfw-toggle');
        const label = document.getElementById('nsfw-label');

        // Restore state
        const stored = localStorage.getItem('maf_manga_nsfw') === 'true';
        toggle.checked = stored;
        if (stored) label.classList.add('active');

        toggle.addEventListener('change', () => {
            localStorage.setItem('maf_manga_nsfw', toggle.checked);
            if (toggle.checked) {
                label.classList.add('active');
            } else {
                label.classList.remove('active');
            }
            // Trigger search if input is not empty
            const query = document.getElementById('search-input').value.trim();
            if (query.length >= 2) {
                performSearch(query);
            }
        });
    }

    // ======================== PROFILES ========================
    function checkProfile() {
        const storedId = localStorage.getItem('maf_manga_profile_id');
        const storedName = localStorage.getItem('maf_manga_profile_name');

        if (storedId && storedName) {
            currentProfile = { id: storedId, name: storedName };
            updateProfileUI();
            loadLibrary();
        } else {
            // Redirect to dedicated login page
            const target = window.location.pathname + window.location.search;
            window.location.href = '/manga/login?redirect=' + encodeURIComponent(target);
        }
    }

    window.openProfileModal = function () {
        document.getElementById('profile-modal').classList.add('active');
        loadProfiles();
    }

    async function loadProfiles() {
        const list = document.getElementById('profile-list');
        list.innerHTML = '<div class="loading-spinner"><div class="spinner"></div></div>';
        try {
            const res = await fetchApi(API_BASE + '/profiles', { silent: true });
            const profiles = await res.json();
            list.innerHTML = '';

            if (profiles.length === 0) {
                list.innerHTML = '<p class="text-muted">No profiles found. Create one with an invite code!</p>';
            }

            profiles.forEach(p => {
                const card = document.createElement('div');
                card.className = 'profile-card';
                if (currentProfile && p.id === currentProfile.id) card.classList.add('active');

                const initials = p.name.substring(0, 2).toUpperCase();
                card.innerHTML = `
                    <div style="display:flex;align-items:center;">
                        <div class="profile-avatar">${escapeHtml(initials)}</div>
                        <div class="profile-name">${escapeHtml(p.name)} ${p.isProtected ? '<span class="profile-locked">🔒</span>' : ''}</div>
                    </div>
                `;
                card.onclick = () => selectProfile(p);
                list.appendChild(card);
            });
        } catch (e) {
            list.innerHTML = '<p class="error-text">Failed to load profiles</p>';
        }
    }

    window.createProfile = async function () {
        const nameInput = document.getElementById('new-profile-name');
        const passInput = document.getElementById('new-profile-password');
        const inviteInput = document.getElementById('new-profile-invite');

        const name = nameInput.value.trim();
        const password = passInput.value;
        const inviteCode = inviteInput.value.trim();

        if (!name) return;

        try {
            const res = await fetchApi(API_BASE + '/profiles', {
                method: 'POST',
                body: JSON.stringify({ name, password, inviteCode })
            });

            if (!res.ok) {
                const error = await res.json();
                showToast(error.error || 'Failed to create profile', 'error');
                return;
            }

            const profile = await res.json();
            // Automatically select the new profile
            currentProfile = { id: profile.id, name: profile.name };
            localStorage.setItem('maf_manga_profile_id', profile.id);
            localStorage.setItem('maf_manga_profile_name', profile.name);
            updateProfileUI();
            
            document.getElementById('profile-modal').classList.remove('active');
            loadLibrary();

            nameInput.value = '';
            passInput.value = '';
            inviteInput.value = '';
        } catch (e) {
            showToast('Failed to create profile: Check invite code', 'error');
        }
    }

    function selectProfile(profile) {
        if (profile.isProtected) {
            openPasswordModal(profile);
            return;
        }
        
        applyProfile(profile.id, profile.name);
    }

    function openPasswordModal(profile) {
        const modal = document.getElementById('password-modal');
        const input = document.getElementById('profile-password-input');
        const nameText = document.getElementById('password-target-name');
        const btn = document.getElementById('password-submit-btn');

        nameText.textContent = `Login to ${profile.name}`;
        input.value = '';
        modal.classList.add('active');

        btn.onclick = async () => {
            const password = input.value;
            try {
                const res = await fetchApi(API_BASE + '/profiles/auth', {
                    method: 'POST',
                    body: JSON.stringify({ profileId: profile.id, password })
                });
                if (res.ok) {
                    modal.classList.remove('active');
                    applyProfile(profile.id, profile.name);
                } else {
                    showToast('Invalid password', 'error');
                }
            } catch (e) {
                showToast('Authentication failed', 'error');
            }
        };
    }

    window.closePasswordModal = function() {
        document.getElementById('password-modal').classList.remove('active');
    }

    function applyProfile(id, name) {
        currentProfile = { id, name };
        localStorage.setItem('maf_manga_profile_id', id);
        localStorage.setItem('maf_manga_profile_name', name);
        updateProfileUI();
        document.getElementById('profile-modal').classList.remove('active');
        loadLibrary();
    }

    function updateProfileUI() {
        const nameEl = document.getElementById('current-profile-name');
        const settingsBtn = document.getElementById('settings-btn');
        if (currentProfile) {
            nameEl.textContent = currentProfile.name;
            if (settingsBtn) settingsBtn.style.display = 'flex';
        } else {
            nameEl.textContent = 'Guest';
            if (settingsBtn) settingsBtn.style.display = 'none';
        }
    }

    window.openSettingsModal = function () {
        document.getElementById('settings-modal').classList.add('active');
        document.getElementById('settings-old-password').value = '';
        document.getElementById('settings-new-password').value = '';
    }

    window.closeSettingsModal = function () {
        document.getElementById('settings-modal').classList.remove('active');
    }

    window.updateProfilePassword = async function () {
        const oldPassEl = document.getElementById('settings-old-password');
        const newPassEl = document.getElementById('settings-new-password');
        const oldPassword = oldPassEl.value;
        const newPassword = newPassEl.value;

        if (!newPassword) {
            showToast('New PIN cannot be empty', 'error');
            return;
        }

        try {
            const res = await fetchApi(API_BASE + '/profiles/update-password', {
                method: 'POST',
                body: JSON.stringify({ profileId: currentProfile.id, oldPassword, newPassword })
            });

            if (res.ok) {
                showToast('PIN updated successfully', 'success');
                closeSettingsModal();
            } else {
                const error = await res.json();
                showToast(error.error || 'Failed to update PIN', 'error');
            }
        } catch (e) {
            showToast('Connection failed', 'error');
        }
    }

    window.logoutProfile = function () {
        localStorage.removeItem('maf_manga_profile_id');
        localStorage.removeItem('maf_manga_profile_name');
        window.location.reload();
    }

    // ======================== PROVIDERS ========================
    async function loadProviders() {
        try {
            const res = await fetchApi(API_BASE + '/providers', { silent: true });
            providers = await res.json();
            const select = document.getElementById('search-provider');
            select.innerHTML = '<option value="">All Sources</option>'; 

            providers.forEach(p => {
                const opt = document.createElement('option');
                opt.value = p.name;
                opt.textContent = p.displayName + (p.isNsfw ? ' 🔞' : '');
                if (p.isNsfw) {
                    opt.dataset.nsfw = "true";
                }
                select.appendChild(opt);
            });

            // Auto-toggle NSFW when selecting an 18+ provider
            select.addEventListener('change', () => {
                const selectedOption = select.options[select.selectedIndex];
                const isNsfw = selectedOption.dataset.nsfw === "true";

                if (isNsfw) {
                    const toggle = document.getElementById('nsfw-toggle');
                    if (!toggle.checked) {
                        toggle.checked = true;
                        const event = new Event('change');
                        toggle.dispatchEvent(event);
                        showToast('Restricted source selected: Sensitive Media enabled', 'info');
                    }
                }
            });

        } catch (e) {
            console.error('Failed to load providers:', e);
        }
    }

    async function fetchFallbackCover(mangaId, provider, placeholderId) {
        try {
            const res = await fetchApi(API_BASE + '/cover', {
                method: 'POST',
                body: JSON.stringify({ mangaId, provider }),
                silent: true
            });
            const data = await res.json();
            if (data.status === 'ok' && data.coverUrl) {
                const placeholder = document.getElementById(placeholderId);
                if (placeholder) {
                    const img = document.createElement('img');
                    img.src = data.coverUrl;
                    img.alt = 'Cover';
                    img.loading = 'lazy';
                    placeholder.parentNode.replaceChild(img, placeholder);
                }
            }
        } catch (e) {
            // best effort, fail silently
        }
    }

    // ======================== LIBRARY ========================
    async function loadLibrary() {
        const grid = document.getElementById('library-grid');
        const empty = document.getElementById('library-empty');
        grid.innerHTML = '';

        try {
            let url = API_BASE + '/library';
            if (currentProfile) {
                url += '?profileId=' + encodeURIComponent(currentProfile.id);
            }
            const res = await fetchApi(url);
            const entries = await res.json();

            if (!entries || entries.length === 0) {
                empty.style.display = 'block';
                return;
            }
            empty.style.display = 'none';

            entries.forEach(entry => {
                grid.appendChild(createMangaCard({
                    id: entry.mangaId,
                    title: entry.title,
                    author: entry.author,
                    coverUrl: entry.coverUrl,
                    provider: entry.provider,
                    downloadedChapters: entry.downloadedChapters,
                    lastReadChapter: entry.lastReadChapter
                }, true));
            });
        } catch (e) {
            console.error('Failed to load library:', e);
            showToast('Failed to load library', 'error');
        }
    }

    // ======================== SEARCH ========================
    function setupSearch() {
        const input = document.getElementById('search-input');
        input.addEventListener('input', () => {
            clearTimeout(searchTimeout);
            const query = input.value.trim();
            if (query.length < 2) {
                hideSearchResults();
                return;
            }
            searchTimeout = setTimeout(() => performSearch(query), 400);
        });

        input.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                input.value = '';
                hideSearchResults();
            }
        });
    }

    async function performSearch(query) {
        const grid = document.getElementById('search-results-grid');
        const section = document.getElementById('search-results-section');
        const provider = document.getElementById('search-provider').value;

        section.style.display = 'block';
        grid.innerHTML = '<div class="loading-spinner"><div class="spinner"></div><span>Searching...</span></div>';

        try {
            let url = API_BASE + '/search?q=' + encodeURIComponent(query) + '&limit=20';
            
            // Bug Fix: Duplicate Parameter removed
            if (provider) url += '&provider=' + encodeURIComponent(provider);

            const nsfw = document.getElementById('nsfw-toggle').checked;
            if (nsfw) url += '&nsfw=true';

            const res = await fetchApi(url);
            const results = await res.json();
            grid.innerHTML = '';

            if (!results || results.length === 0) {
                grid.innerHTML = '<div class="empty-state"><div class="icon">🔍</div><h3>No results found</h3></div>';
                return;
            }

            results.forEach(manga => {
                grid.appendChild(createMangaCard(manga, false));
            });
        } catch (e) {
            console.error('Search failed:', e);
            grid.innerHTML = '<div class="empty-state"><h3>Search failed</h3></div>';
        }
    }

    function hideSearchResults() {
        document.getElementById('search-results-section').style.display = 'none';
        document.getElementById('search-results-grid').innerHTML = '';
    }

    // ======================== MANGA CARD ========================
    function createMangaCard(manga, isLibrary) {
        const card = document.createElement('div');
        card.className = 'manga-card';
        card.onclick = () => openMangaDetail(manga.id, manga.provider);

        let coverImg;
        if (manga.coverUrl) {
            let src = getProxiedUrl(manga.coverUrl);
            coverImg = `<img src="${escapeHtml(src)}" alt="${escapeHtml(manga.title)}" loading="lazy">`;
        } else {
            const uniqueId = `cover-${String(manga.id).replace(/[^a-zA-Z0-9]/g, '')}-${manga.provider}`;
            coverImg = `<div id="${uniqueId}" style="display:flex;align-items:center;justify-content:center;height:100%;color:var(--text-muted);font-size:2rem;">📖</div>`;
            fetchFallbackCover(manga.id, manga.provider, uniqueId);
        }

        let progressHtml = '';
        if (isLibrary && manga.downloadedChapters > 0) {
            progressHtml = `<div class="progress-indicator"><div class="progress-bar" style="width:100%"></div></div>`;
        }

        let metaHtml = '';
        if (isLibrary && manga.downloadedChapters > 0) {
            metaHtml = `<span class="tag">📥 ${manga.downloadedChapters} ch</span>`;
        }
        if (isLibrary && manga.lastReadChapter) {
            metaHtml += `<span class="tag">📖 Ch.${escapeHtml(manga.lastReadChapter)}</span>`;
        }

        card.innerHTML = `
            <div class="manga-card-cover">
                ${coverImg}
                <span class="provider-badge">${escapeHtml(manga.provider || '')}</span>
                ${progressHtml}
            </div>
            <div class="manga-card-info">
                <div class="manga-card-title">${escapeHtml(manga.title || 'Unknown')}</div>
                <div class="manga-card-author">${escapeHtml(manga.author || '')}</div>
                ${metaHtml ? `<div class="manga-card-meta">${metaHtml}</div>` : ''}
            </div>
        `;
        return card;
    }

    // ======================== MANGA DETAIL MODAL ========================
    async function openMangaDetail(mangaId, provider) {
        const modal = document.getElementById('detail-modal');
        modal.classList.add('active');

        document.getElementById('modal-title').textContent = 'Loading...';
        document.getElementById('modal-author').textContent = '';
        document.getElementById('modal-description').textContent = '';
        document.getElementById('modal-tags').innerHTML = '';
        document.getElementById('modal-chapter-list').innerHTML = '';
        document.getElementById('modal-chapters-loading').style.display = 'flex';
        document.getElementById('modal-chapter-count').textContent = '';

        try {
            const res = await fetchApi(
                API_BASE + '/details?id=' + encodeURIComponent(mangaId) +
                '&provider=' + encodeURIComponent(provider) +
                (currentProfile ? '&profileId=' + encodeURIComponent(currentProfile.id) : '')
            );
            const data = await res.json();
            currentManga = data;

            const info = data.details.info;
            document.getElementById('modal-title').textContent = info.title;
            document.getElementById('modal-author').textContent = info.author ? 'by ' + info.author : '';
            document.getElementById('modal-description').textContent = info.description || '';

            document.getElementById('modal-cover').src = getProxiedUrl(info.coverUrl);

            // Tags
            const tagsEl = document.getElementById('modal-tags');
            tagsEl.innerHTML = '';
            if (info.tags) {
                info.tags.slice(0, 8).forEach(tag => {
                    const span = document.createElement('span');
                    span.className = 'tag';
                    span.textContent = tag;
                    tagsEl.appendChild(span);
                });
            }

            // Library button state
            const addBtn = document.getElementById('modal-add-btn');
            const removeBtn = document.getElementById('modal-remove-btn') || document.createElement('button');

            if (!document.getElementById('modal-remove-btn')) {
                removeBtn.id = 'modal-remove-btn';
                removeBtn.className = 'btn btn-danger';
                removeBtn.textContent = '🗑 Remove';
                removeBtn.onclick = () => removeFromLibrary();
                addBtn.parentNode.insertBefore(removeBtn, addBtn.nextSibling);
            }

            if (data.inLibrary) {
                addBtn.textContent = '✓ In Library';
                addBtn.classList.remove('btn-primary');
                addBtn.classList.add('btn-secondary');
                addBtn.disabled = true;
                removeBtn.style.display = 'inline-block';
            } else {
                addBtn.textContent = '➕ Add to Library';
                addBtn.classList.remove('btn-secondary');
                addBtn.classList.add('btn-primary');
                addBtn.disabled = false;
                removeBtn.style.display = 'none';
            }

            // Chapters
            const chapters = data.details.chapters || [];
            document.getElementById('modal-chapter-count').textContent = `(${chapters.length} chapters)`;
            document.getElementById('modal-chapters-loading').style.display = 'none';

            const chList = document.getElementById('modal-chapter-list');
            chList.innerHTML = '';
            const downloadedSet = new Set(data.downloadedChapters || []);

            chapters.forEach(ch => {
                const li = document.createElement('li');
                const isDownloaded = downloadedSet.has(ch.id);
                li.className = 'chapter-item' + (isDownloaded ? ' downloaded' : '');

                let actionBtns = '';

                if (isDownloaded) {
                    actionBtns += `<button class="btn btn-sm btn-danger" style="margin-right:5px;" onclick="event.stopPropagation(); deleteChapter('${escapeAttr(ch.id)}', '${escapeAttr(ch.chapter)}')">🗑</button>`;
                    actionBtns += `<span class="tag" style="margin-right:10px;">✓ Downloaded</span>`;
                } else {
                    actionBtns += `<button class="btn btn-sm btn-primary" style="margin-right:5px;" onclick="event.stopPropagation(); downloadChapter('${escapeAttr(ch.id)}', '${escapeAttr(ch.chapter)}')">⬇</button>`;
                }

                li.innerHTML = `
                    <span class="chapter-num">Ch. ${escapeHtml(ch.chapter)}</span>
                    <span class="chapter-title">${escapeHtml(ch.title || '')}</span>
                    <span class="chapter-group">${escapeHtml(ch.scanlationGroup || '')}</span>
                    <div class="chapter-actions">
                        ${actionBtns}
                        <button class="btn btn-sm btn-secondary" onclick="event.stopPropagation(); readChapter('${escapeAttr(ch.id)}')">Read</button>
                    </div>
                `;
                li.addEventListener('click', () => readChapter(ch.id));
                chList.appendChild(li);
            });

        } catch (e) {
            console.error('Failed to load manga details:', e);
            document.getElementById('modal-title').textContent = 'Error loading manga';
            document.getElementById('modal-chapters-loading').style.display = 'none';
            showToast('Failed to load manga details', 'error');
        }
    }

    // ======================== ACTIONS ========================
    window.closeModal = function () {
        document.getElementById('detail-modal').classList.remove('active');
        currentManga = null;
    };

    document.getElementById('detail-modal').addEventListener('click', (e) => {
        if (e.target.classList.contains('modal-overlay')) closeModal();
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeModal();
    });

    window.addToLibrary = async function () {
        if (!currentManga) return;
        const info = currentManga.details.info;
        try {
            await fetchApi(API_BASE + '/library', {
                method: 'POST',
                body: JSON.stringify({
                    id: info.id,
                    title: info.title,
                    author: info.author,
                    coverUrl: info.coverUrl,
                    description: info.description,
                    provider: info.provider
                })
            });

            const addBtn = document.getElementById('modal-add-btn');
            addBtn.textContent = '✓ In Library';
            addBtn.classList.remove('btn-primary');
            addBtn.classList.add('btn-secondary');
            showToast('Added to library: ' + info.title, 'success');

            loadLibrary();
        } catch (e) {
            showToast('Failed to add to library', 'error');
        }
    };

    window.removeFromLibrary = async function () {
        if (!currentManga || !confirm('Are you sure you want to remove this manga from your library? usage data + downloaded files will be lost.')) return;

        const info = currentManga.details.info;
        try {
            await fetchApi(API_BASE + '/library?id=' + encodeURIComponent(info.id) + '&provider=' + encodeURIComponent(info.provider), {
                method: 'DELETE'
            });

            showToast('Removed from library', 'success');
            closeModal();
            loadLibrary();
        } catch (e) {
            showToast('Failed to remove from library', 'error');
        }
    };

    window.downloadAllChapters = async function () {
        if (!currentManga) return;
        const info = currentManga.details.info;

        try {
            await fetchApi(API_BASE + '/download', {
                method: 'POST',
                body: JSON.stringify({
                    mangaId: info.id,
                    provider: info.provider
                })
            });
            showToast('Download started for: ' + info.title, 'info');
        } catch (e) {
            showToast('Failed to start download', 'error');
        }
    };

    window.toggleDownloadDropdown = function () {
        const dd = document.getElementById('download-dropdown');
        if (dd) dd.style.display = dd.style.display === 'none' ? 'block' : 'none';
    };

    document.addEventListener('click', (e) => {
        const dd = document.getElementById('download-dropdown');
        const btn = document.getElementById('modal-download-btn');
        if (dd && dd.style.display === 'block' && !dd.contains(e.target) && !btn.contains(e.target)) {
            dd.style.display = 'none';
        }
    });

    window.showRangeSelector = function () {
        const container = document.getElementById('range-selector-container');
        if (!container || !currentManga) return;

        const chapters = currentManga.details.chapters;
        const startSelect = document.getElementById('range-start');
        const endSelect = document.getElementById('range-end');

        startSelect.innerHTML = '';
        endSelect.innerHTML = '';

        chapters.forEach((ch, index) => {
            const label = `Ch. ${ch.chapter} - ${ch.title || ''}`;
            const opt1 = new Option(label, index);
            const opt2 = new Option(label, index);
            startSelect.add(opt1);
            endSelect.add(opt2);
        });

        if (chapters.length > 0) {
            startSelect.selectedIndex = chapters.length - 1; 
            endSelect.selectedIndex = 0;
        }

        container.style.display = 'block';
    };

    window.hideRangeSelector = function () {
        const container = document.getElementById('range-selector-container');
        if (container) container.style.display = 'none';
    };

    window.downloadRange = async function () {
        if (!currentManga) return;

        const startIdx = parseInt(document.getElementById('range-start').value);
        const endIdx = parseInt(document.getElementById('range-end').value);
        const chapters = currentManga.details.chapters;

        const min = Math.min(startIdx, endIdx);
        const max = Math.max(startIdx, endIdx);

        const selectedChapters = chapters.slice(min, max + 1);
        const chapterIds = selectedChapters.map(ch => ch.id);

        if (chapterIds.length === 0) return;

        const info = currentManga.details.info;

        try {
            await fetchApi(API_BASE + '/download', {
                method: 'POST',
                body: JSON.stringify({
                    mangaId: info.id,
                    provider: info.provider,
                    chapterIds: chapterIds
                })
            });
            showToast(`Queued ${chapterIds.length} chapters for download`, 'info');
            hideRangeSelector();
        } catch (e) {
            showToast('Failed to queue download', 'error');
        }
    };

    window.downloadChapter = async function (chapterId, chapterNum) {
        if (!currentManga) return;
        const info = currentManga.details.info;

        try {
            await fetchApi(API_BASE + '/download', {
                method: 'POST',
                body: JSON.stringify({
                    mangaId: info.id,
                    provider: info.provider,
                    chapterIds: [chapterId]
                })
            });
            showToast('Queued download: Ch. ' + chapterNum, 'info');
        } catch (e) {
            showToast('Failed to queue download', 'error');
        }
    };

    window.deleteChapter = async function (chapterId, chapterNum) {
        if (!currentManga || !confirm('Delete Chapter ' + chapterNum + '?')) return;
        const info = currentManga.details.info;

        try {
            await fetchApi(API_BASE + '/chapter?mangaId=' + encodeURIComponent(info.id) +
                '&chapterId=' + encodeURIComponent(chapterId) +
                '&provider=' + encodeURIComponent(info.provider) +
                '&chapterNum=' + encodeURIComponent(chapterNum), {
                method: 'DELETE'
            });

            showToast('Deleted Chapter ' + chapterNum, 'success');
            openMangaDetail(info.id, info.provider);
        } catch (e) {
            showToast('Failed to delete chapter', 'error');
        }
    };

    window.startReading = function () {
        if (!currentManga) return;
        const info = currentManga.details.info;
        const chapters = currentManga.details.chapters;
        if (!chapters || chapters.length === 0) {
            showToast('No chapters available', 'error');
            return;
        }

        let startChapter = chapters[0];
        if (currentManga.progress && currentManga.progress.lastChapterId) {
            const found = chapters.find(ch => ch.id === currentManga.progress.lastChapterId);
            if (found) startChapter = found;
        }

        openReader(info.id, info.provider, startChapter.id, info.title);
    };

    // Bug Fix: Unbenutzten 'chapterNum' Parameter entfernt
    window.readChapter = function (chapterId) {
        if (!currentManga) return;
        const info = currentManga.details.info;
        openReader(info.id, info.provider, chapterId, info.title);
    };

    function openReader(mangaId, provider, chapterId, title) {
        const params = new URLSearchParams({
            mangaId, provider, chapterId, title: title || ''
        });
        window.location.href = '/manga/reader?' + params.toString();
    }

    // ======================== UTILITIES ========================
    function showToast(message, type = 'info') {
        const container = document.getElementById('toast-container');
        const toast = document.createElement('div');
        toast.className = 'toast ' + type;
        toast.textContent = message;
        container.appendChild(toast);
        setTimeout(() => {
            toast.style.opacity = '0';
            toast.style.transform = 'translateX(20px)';
            toast.style.transition = 'all 0.3s ease';
            setTimeout(() => toast.remove(), 300);
        }, 3000);
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function escapeAttr(str) {
        if (!str) return '';
        return str.replace(/'/g, "\\'").replace(/"/g, '&quot;');
    }

    // Settings Tab Switching
    window.switchTab = function(tabId) {
        document.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.remove('active');
            if (btn.getAttribute('onclick').includes(tabId)) {
                btn.classList.add('active');
            }
        });
        
        document.querySelectorAll('.settings-tab').forEach(tab => {
            tab.style.display = 'none';
        });
        document.getElementById(tabId).style.display = 'block';
    }

})();
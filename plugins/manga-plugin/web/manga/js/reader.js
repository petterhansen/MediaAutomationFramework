/**
 * Reader.js — Immersive Manga Reader Frontend
 * Supports vertical scroll (webtoon) and page-by-page reading modes.
 */
(function () {
    'use strict';

    // ======================== STATE ========================
    const API_BASE = '/api/manga';
    let mangaId = '';
    let provider = '';
    let mangaTitle = '';
    let currentChapterId = '';
    let chapters = [];           // All chapters for this manga
    let pages = [];              // Current chapter page URLs
    let currentPage = 0;         // 0-indexed
    let mode = 'vertical';       // 'vertical' or 'page'
    let controlsVisible = false;
    let controlsTimeout = null;
    let sidebarCollapsed = false;
    let preloadCount = 3;
    let profileId = localStorage.getItem('maf_manga_profile_id');

    function getToken() {
        return localStorage.getItem('maf_token') || '';
    }

    function apiHeaders() {
        return {
            'X-MAF-Token': getToken()
        };
    }

    async function fetchApi(url, options = {}) {
        if (!options.headers) options.headers = apiHeaders();
        const res = await fetch(url, options);
        if (res.status === 401) {
            window.location.href = '/login?redirect=' + encodeURIComponent(window.location.pathname + window.location.search);
            throw new Error('Unauthorized');
        }
        return res;
    }

    // ======================== INIT ========================
    document.addEventListener('DOMContentLoaded', () => {
        parseUrlParams();
        setupKeyboardShortcuts();
        setupTapZones();
        setupSlider();
        loadMangaDetails();
    });

    function parseUrlParams() {
        const p = new URLSearchParams(window.location.search);
        mangaId = p.get('mangaId') || '';
        provider = p.get('provider') || '';
        currentChapterId = p.get('chapterId') || '';
        mangaTitle = p.get('title') || '';

        document.getElementById('reader-manga-title').textContent = mangaTitle || 'Loading...';
        document.title = (mangaTitle || 'Reader') + ' — Rohan';
    }

    // ======================== LOAD DATA ========================
    async function loadMangaDetails() {
        try {
            const res = await fetchApi(
                API_BASE + '/details?id=' + encodeURIComponent(mangaId) +
                '&provider=' + encodeURIComponent(provider) +
                (profileId ? '&profileId=' + encodeURIComponent(profileId) : '')
            );
            const data = await res.json();
            chapters = data.details.chapters || [];

            document.getElementById('reader-manga-title').textContent = data.details.info.title;
            mangaTitle = data.details.info.title;
            document.title = mangaTitle + ' — Rohan';

            renderChapterList();

            // Load the specified chapter
            if (currentChapterId) {
                loadChapter(currentChapterId);
            } else if (chapters.length > 0) {
                loadChapter(chapters[0].id);
            }
        } catch (e) {
            console.error('Failed to load manga details:', e);
            showToast('Failed to load manga', 'error');
        }
    }

    function renderChapterList() {
        const list = document.getElementById('reader-chapter-list');
        list.innerHTML = '';

        chapters.forEach(ch => {
            const li = document.createElement('li');
            li.className = 'chapter-item' + (ch.id === currentChapterId ? ' active' : '');
            li.innerHTML = `
                <span class="chapter-num">Ch. ${escapeHtml(ch.chapter)}</span>
                <span class="chapter-title">${escapeHtml(ch.title || '')}</span>
            `;
            li.addEventListener('click', () => loadChapter(ch.id));
            list.appendChild(li);
        });
    }

    let isLoading = false; // Prevent double loads

    async function loadChapter(chapterId, targetPage = 0) {
        if (isLoading) return;
        isLoading = true;

        currentChapterId = chapterId;
        pages = [];

        // Update UI
        const chapter = chapters.find(ch => ch.id === chapterId);
        const chapterLabel = chapter ? 'Chapter ' + chapter.chapter : chapterId;
        document.getElementById('reader-current-chapter').textContent = chapterLabel;

        // Highlight active chapter in sidebar
        document.querySelectorAll('#reader-chapter-list .chapter-item').forEach(li => {
            li.classList.remove('active');
        });
        const idx = chapters.findIndex(ch => ch.id === chapterId);
        const items = document.querySelectorAll('#reader-chapter-list .chapter-item');
        if (items[idx]) {
            items[idx].classList.add('active');
            items[idx].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }

        // Show loading
        const container = document.getElementById('page-container');
        container.innerHTML = '<div class="loading-spinner" id="reader-loading"><div class="spinner"></div><span>Loading chapter...</span></div>';

        try {
            // Build page request URL
            let url = API_BASE + '/pages?chapterId=' + encodeURIComponent(chapterId) + '&provider=' + encodeURIComponent(provider);
            if (chapter) {
                const slug = mangaTitle.replace(/[^a-zA-Z0-9 ]/g, '').trim().replace(/\s+/g, '-').toLowerCase();
                url += '&mangaSlug=' + encodeURIComponent(slug) + '&chapterNum=' + encodeURIComponent(chapter.chapter);
            }

            const res = await fetchApi(url);
            const data = await res.json();
            pages = (data.pages || []).map(url => {
                if (url.startsWith('/')) {
                    return url + (url.includes('?') ? '&' : '?') + 'token=' + getToken();
                }
                return url;
            });

            if (pages.length === 0) {
                container.innerHTML = '<div class="empty-state"><div class="icon">📄</div><h3>No pages found</h3><p>This chapter may not be available.</p></div>';
                isLoading = false;
                return;
            }

            // Determine start page
            if (targetPage === 'last') {
                currentPage = Math.max(0, pages.length - 1);
            } else {
                currentPage = Math.max(0, Math.min(targetPage, pages.length - 1));
            }

            renderPages();

            // Scroll to position
            if (mode === 'vertical') {
                const content = document.getElementById('reader-content');
                if (targetPage === 'last') {
                    // Scroll to bottom
                    setTimeout(() => {
                        content.scrollTop = content.scrollHeight;
                    }, 50);
                } else {
                    content.scrollTop = 0;
                }
            }

            updateControls();

            // Save reading progress
            saveProgress(chapterId, chapter ? chapter.chapter : '0', currentPage);

            // Update URL without reload
            const params = new URLSearchParams({ mangaId, provider, chapterId, title: mangaTitle });
            history.replaceState(null, '', '/manga/reader?' + params.toString());

        } catch (e) {
            console.error('Failed to load chapter:', e);
            container.innerHTML = '<div class="empty-state"><h3>Failed to load chapter</h3></div>';
            showToast('Failed to load chapter', 'error');
        } finally {
            isLoading = false;
        }
    }

    // ======================== RENDERING ========================
    function renderPages() {
        const container = document.getElementById('page-container');
        container.innerHTML = '';

        if (mode === 'vertical') {
            renderVerticalMode(container);
        } else {
            renderPageMode(container);
        }
    }

    function renderVerticalMode(container) {
        pages.forEach((url, i) => {
            const img = document.createElement('img');
            img.dataset.index = i;
            img.alt = `Page ${i + 1}`;
            img.loading = i < 5 ? 'eager' : 'lazy';
            img.src = url;
            img.style.cssText = 'width:100%;display:block;min-height:200px;background:var(--bg-tertiary);';
            img.onerror = () => {
                img.style.minHeight = '100px';
                img.alt = `Page ${i + 1} failed to load`;
            };
            container.appendChild(img);
        });

        const readerContent = document.getElementById('reader-content');

        // Remove old listeners to prevent duplicates if function called multiple times? 
        // Actually this function is called on render, so cleaner to use named function or direct assignment
        readerContent.onscroll = handleVerticalScroll;

        // Add wheel listener for top-of-page navigation (debounced)
        readerContent.onwheel = handleVerticalWheel;
    }

    let lastScrollTime = 0;
    function handleVerticalScroll() {
        if (isLoading) return;
        updateCurrentPageFromScroll();

        const readerContent = document.getElementById('reader-content');
        const scrollBottom = readerContent.scrollTop + readerContent.clientHeight;
        const totalHeight = readerContent.scrollHeight;

        // Detect Bottom -> Next Chapter
        if (scrollBottom >= totalHeight - 10) { // Tolerance
            const now = Date.now();
            if (now - lastScrollTime > 1000) { // Simple debounce
                lastScrollTime = now;
                nextChapter();
            }
        }
    }

    function handleVerticalWheel(e) {
        if (isLoading) return;
        const readerContent = document.getElementById('reader-content');

        // Detect Top -> Prev Chapter (only if scrolling UP at top)
        if (readerContent.scrollTop <= 0 && e.deltaY < 0) {
            const now = Date.now();
            if (now - lastScrollTime > 1000) {
                lastScrollTime = now;
                prevChapter();
            }
        }
    }

    function renderPageMode(container) {
        if (pages.length === 0) return;

        const img = document.createElement('img');
        img.src = pages[currentPage];
        img.alt = `Page ${currentPage + 1}`;
        img.style.cssText = 'max-width:100%;max-height:100vh;object-fit:contain;';
        img.onerror = () => { img.alt = `Page ${currentPage + 1} failed to load`; };
        container.appendChild(img);

        // Preload adjacent pages
        for (let i = 1; i <= preloadCount; i++) {
            if (currentPage + i < pages.length) {
                const link = document.createElement('link');
                link.rel = 'prefetch';
                link.href = pages[currentPage + i];
                document.head.appendChild(link);
            }
        }
    }

    function updateCurrentPageFromScroll() {
        const readerContent = document.getElementById('reader-content');
        const images = document.querySelectorAll('#page-container img');
        const viewHeight = readerContent.clientHeight;

        let visiblePage = 0;
        images.forEach((img, i) => {
            const rect = img.getBoundingClientRect();
            // Simple logic: if image top is roughly near top of viewport
            if (rect.top < viewHeight * 0.5 && rect.bottom > 0) {
                visiblePage = i;
            }
        });

        if (visiblePage !== currentPage) {
            currentPage = visiblePage;
            // updateControls(); // Performance: don't update full controls on every scroll tick if possible

            // Debounced progress save
            clearTimeout(window._progressSaveTimeout);
            window._progressSaveTimeout = setTimeout(() => {
                updateControls(); // Update UI when scrolling stops/slows
                const ch = chapters.find(c => c.id === currentChapterId);
                saveProgress(currentChapterId, ch ? ch.chapter : '0', currentPage);
            }, 500);
        }
    }

    // ======================== NAVIGATION ========================
    window.nextPage = function () {
        if (mode === 'vertical') {
            const readerContent = document.getElementById('reader-content');
            readerContent.scrollBy({ top: readerContent.clientHeight * 0.9, behavior: 'smooth' });
        } else {
            if (currentPage < pages.length - 1) {
                currentPage++;
                renderPages();
                updateControls();
            } else {
                nextChapter();
            }
        }
    };

    window.prevPage = function () {
        if (mode === 'vertical') {
            const readerContent = document.getElementById('reader-content');
            readerContent.scrollBy({ top: -readerContent.clientHeight * 0.9, behavior: 'smooth' });
        } else {
            if (currentPage > 0) {
                currentPage--;
                renderPages();
                updateControls();
            } else {
                prevChapter();
            }
        }
    };

    window.nextChapter = function () {
        if (isLoading) return;
        const idx = chapters.findIndex(ch => ch.id === currentChapterId);
        if (idx >= 0 && idx < chapters.length - 1) {
            showToast('Loading next chapter...', 'info');
            loadChapter(chapters[idx + 1].id, 0); // Start at beginning
        } else {
            showToast('Last chapter reached', 'info');
        }
    };

    window.prevChapter = function () {
        if (isLoading) return;
        const idx = chapters.findIndex(ch => ch.id === currentChapterId);
        if (idx > 0) {
            showToast('Loading previous chapter...', 'info');
            loadChapter(chapters[idx - 1].id, 'last'); // Start at END
        } else {
            showToast('First chapter reached', 'info');
        }
    };

    // ======================== CONTROLS ========================
    function updateControls() {
        const counter = document.getElementById('page-counter');
        counter.textContent = `${currentPage + 1} / ${pages.length}`;

        const slider = document.getElementById('page-slider');
        slider.max = pages.length;
        slider.value = currentPage + 1;
    }

    function setupSlider() {
        const slider = document.getElementById('page-slider');
        slider.addEventListener('input', () => {
            const page = parseInt(slider.value) - 1;
            if (page >= 0 && page < pages.length) {
                currentPage = page;
                if (mode === 'vertical') {
                    const images = document.querySelectorAll('#page-container img');
                    if (images[page]) {
                        images[page].scrollIntoView({ behavior: 'smooth', block: 'start' });
                    }
                } else {
                    renderPages();
                }
                updateControls();
            }
        });
    }

    function showControls() {
        const controls = document.getElementById('reader-controls');
        controls.classList.add('visible');
        controlsVisible = true;
        clearTimeout(controlsTimeout);
        controlsTimeout = setTimeout(hideControls, 4000);
    }

    function hideControls() {
        const controls = document.getElementById('reader-controls');
        controls.classList.remove('visible');
        controlsVisible = false;
    }

    function toggleControls() {
        if (controlsVisible) hideControls();
        else showControls();
    }

    // ======================== MODE SWITCHING ========================
    window.setMode = function (newMode) {
        mode = newMode;
        const content = document.getElementById('reader-content');

        document.getElementById('mode-vertical').classList.toggle('active', mode === 'vertical');
        document.getElementById('mode-page').classList.toggle('active', mode === 'page');

        content.classList.toggle('vertical-mode', mode === 'vertical');
        content.classList.toggle('page-mode', mode === 'page');

        // Re-scroll to top when switching to vertical
        if (mode === 'vertical') {
            content.scrollTop = 0;
            content.style.overflow = 'auto';
        } else {
            content.style.overflow = 'hidden';
        }

        renderPages();
        updateControls();
    };

    // ======================== SIDEBAR ========================
    window.toggleSidebar = function () {
        const sidebar = document.getElementById('reader-sidebar');
        sidebarCollapsed = !sidebarCollapsed;
        sidebar.classList.toggle('collapsed', sidebarCollapsed);
    };

    // ======================== FULLSCREEN ========================
    window.toggleFullscreen = function () {
        if (!document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(() => { });
        } else {
            document.exitFullscreen();
        }
    };

    // ======================== KEYBOARD SHORTCUTS ========================
    function setupKeyboardShortcuts() {
        document.addEventListener('keydown', (e) => {
            // Don't trigger shortcuts when typing in inputs
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

            switch (e.key) {
                case 'ArrowRight':
                case 'd':
                    e.preventDefault();
                    nextPage();
                    break;
                case 'ArrowLeft':
                case 'a':
                    e.preventDefault();
                    prevPage();
                    break;
                case 'ArrowDown':
                    if (mode === 'page') {
                        e.preventDefault();
                        nextPage();
                    }
                    break;
                case 'ArrowUp':
                    if (mode === 'page') {
                        e.preventDefault();
                        prevPage();
                    }
                    break;
                case 'f':
                case 'F':
                    e.preventDefault();
                    toggleFullscreen();
                    break;
                case 's':
                case 'S':
                    e.preventDefault();
                    toggleSidebar();
                    break;
                case 'm':
                case 'M':
                    e.preventDefault();
                    setMode(mode === 'vertical' ? 'page' : 'vertical');
                    break;
                case 'Escape':
                    hideControls();
                    break;
                case ' ':
                    e.preventDefault();
                    toggleControls();
                    break;
                case ']':
                    e.preventDefault();
                    nextChapter();
                    break;
                case '[':
                    e.preventDefault();
                    prevChapter();
                    break;
            }
        });
    }

    // ======================== TAP ZONES ========================
    function setupTapZones() {
        // Clicking the top/bottom zones toggles controls
        document.getElementById('tap-zone-top').addEventListener('click', toggleControls);
        document.getElementById('tap-zone-bottom').addEventListener('click', toggleControls);

        // In page mode, clicking left/right sides navigates
        document.getElementById('reader-content').addEventListener('click', (e) => {
            if (mode !== 'page') {
                toggleControls();
                return;
            }

            const rect = e.currentTarget.getBoundingClientRect();
            const x = e.clientX - rect.left;
            const width = rect.width;

            if (x < width * 0.3) {
                prevPage();
            } else if (x > width * 0.7) {
                nextPage();
            } else {
                toggleControls();
            }
        });

        // Show controls on mouse move near bottom
        document.addEventListener('mousemove', (e) => {
            if (e.clientY > window.innerHeight - 80) {
                showControls();
            }
        });
    }

    // ======================== PROGRESS ========================
    async function saveProgress(chapterId, chapterNum, page) {
        try {
            await fetchApi(API_BASE + '/progress', {
                method: 'POST',
                body: JSON.stringify({
                    mangaId, provider, chapterId, chapterNum,
                    page: String(page),
                    profileId: profileId || ''
                })
            });
        } catch (e) {
            // Silent fail — progress saving is best-effort
        }
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
        }, 2500);
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
})();

const API_BASE = '/api/manga';
let currentGenre = '';
let currentPage = 1;
let currentSort = 'popular';
let currentSource = '';
const PAGE_LIMIT = 24; // Multi-row friendly for standard grids

// --- Initialize ---
document.addEventListener('DOMContentLoaded', () => {
    // Auth Check
    const profileId = localStorage.getItem('maf_manga_profile_id');
    const profileName = localStorage.getItem('maf_manga_profile_name');
    
    if (!profileId || !profileName || profileId === 'null' || profileId === 'undefined') {
        const currentPath = window.location.pathname + window.location.search;
        window.location.href = `/manga/login?redirect=${encodeURIComponent(currentPath)}`;
        return;
    }
    
    loadProfile();
    fetchGenres();
    fetchProviders();
    initMangaWall();

    // Event Listeners for Controls
    document.getElementById('sort-select').onchange = (e) => {
        currentSort = e.target.value;
        currentPage = 1;
        fetchResults();
    };

    document.getElementById('source-select').onchange = (e) => {
        currentSource = e.target.value;
        currentPage = 1;
        fetchResults();
    };

    document.getElementById('prev-btn').onclick = () => {
        if (currentPage > 1) {
            currentPage--;
            fetchResults();
            window.scrollTo({ top: document.getElementById('genre-results-title').offsetTop - 100, behavior: 'smooth' });
        }
    };

    document.getElementById('next-btn').onclick = () => {
        currentPage++;
        fetchResults();
        window.scrollTo({ top: document.getElementById('genre-results-title').offsetTop - 100, behavior: 'smooth' });
    };
});

function loadProfile() {
    const name = localStorage.getItem('maf_manga_profile_name');
    if (name) {
        document.getElementById('current-profile-name').textContent = name;
    }
}

async function fetchProviders() {
    try {
        const res = await fetch(`${API_BASE}/providers`);
        const providers = await res.json();
        const select = document.getElementById('source-select');
        select.innerHTML = '<option value="">All Sources</option>';
        providers.forEach(p => {
            const opt = document.createElement('option');
            opt.value = p.name;
            opt.textContent = p.displayName;
            select.appendChild(opt);
        });
    } catch (err) {
        console.error('Failed to fetch providers', err);
    }
}

async function fetchGenres() {
    const grid = document.getElementById('genre-grid');
    const loading = document.getElementById('loading');
    
    try {
        loading.style.display = 'flex';
        const res = await fetch(`${API_BASE}/genres`);
        const genres = await res.json();
        
        grid.innerHTML = '';
        genres.forEach(genre => {
            const chip = document.createElement('div');
            chip.className = 'genre-chip';
            chip.textContent = genre;
            chip.onclick = () => selectGenre(genre, chip);
            grid.appendChild(chip);
        });
    } catch (err) {
        console.error('Failed to fetch genres', err);
    } finally {
        loading.style.display = 'none';
    }
}

async function selectGenre(genre, element) {
    currentGenre = genre;
    currentPage = 1;

    // UI Update
    document.querySelectorAll('.genre-chip').forEach(c => c.classList.remove('active'));
    element.classList.add('active');
    
    document.getElementById('genre-results-title').style.display = 'block';
    document.getElementById('genre-toolbar').style.display = 'flex';
    document.getElementById('active-genre-name').textContent = genre;
    
    fetchResults();
}

async function fetchResults() {
    const grid = document.getElementById('results-grid');
    const loading = document.getElementById('loading');
    const noResults = document.getElementById('no-results-msg');
    const pagination = document.getElementById('pagination-footer');
    const pageDisplay = document.getElementById('page-display');
    const prevBtn = document.getElementById('prev-btn');
    
    try {
        grid.innerHTML = '';
        if (noResults) noResults.style.display = 'none';
        pagination.style.display = 'none';
        loading.style.display = 'flex';
        
        let url = `${API_BASE}/genre?genre=${encodeURIComponent(currentGenre)}&limit=${PAGE_LIMIT}&page=${currentPage}`;
        if (currentSort) url += `&sort=${currentSort}`;
        if (currentSource) url += `&source=${currentSource}`;

        const res = await fetch(url);
        const results = await res.json();
        
        if (!results || results.length === 0) {
            if (currentPage === 1) {
                if (noResults) {
                    noResults.style.display = 'block';
                    noResults.querySelector('h3').textContent = `No manga found for "${currentGenre}"`;
                }
            } else {
                // If we're on page 2+ and get nothing, go back
                currentPage--;
                fetchResults();
                return;
            }
            return;
        }
        
        results.forEach(manga => {
            const card = createMangaCard(manga);
            grid.appendChild(card);
        });

        // Update Pagination UI
        pagination.style.display = 'flex';
        pageDisplay.textContent = `Page ${currentPage}`;
        prevBtn.disabled = currentPage === 1;
        prevBtn.style.opacity = currentPage === 1 ? '0.5' : '1';
        prevBtn.style.cursor = currentPage === 1 ? 'default' : 'pointer';
        
    } catch (err) {
        console.error('Failed to fetch genre results', err);
    } finally {
        loading.style.display = 'none';
    }
}

function createMangaCard(manga) {
    const card = document.createElement('div');
    card.className = 'manga-card';
    card.onclick = () => {
        window.location.href = `/manga?id=${manga.id}&provider=${manga.provider}`;
    };

    let coverSrc = manga.coverUrl || '';
    if (coverSrc && !coverSrc.includes('/api/manga/proxy-image') && (coverSrc.includes('mangadex') || coverSrc.includes('mangapill') || coverSrc.startsWith('http'))) {
        coverSrc = `${API_BASE}/proxy-image?url=${encodeURIComponent(coverSrc)}`;
    }

    card.innerHTML = `
        <div class="manga-card-cover">
            <img src="${coverSrc}" alt="${manga.title}" loading="lazy">
            <span class="provider-badge">${manga.provider || ''}</span>
        </div>
        <div class="manga-card-info">
            <div class="manga-card-title">${manga.title || 'Unknown'}</div>
            <div class="manga-card-author">${manga.author || ''}</div>
        </div>
    `;

    return card;
}

// --- Background Wall Logic ---
async function initMangaWall() {
    const wall = document.getElementById('background-wall');
    if (!wall) return;

    try {
        const res = await fetch(API_BASE + '/random?limit=100');
        const randoms = await res.json();
        
        const covers = randoms.map(m => {
            let src = m.coverUrl;
            if (src && !src.includes('/api/manga/proxy-image') && (src.includes('mangadex') || src.includes('mangapill') || src.startsWith('http'))) {
                return `${API_BASE}/proxy-image?url=${encodeURIComponent(src)}`;
            }
            return src;
        }).filter(src => src);

        if (covers.length === 0) return;

        const uniqueCount = 200;
        const totalCount = 400; 
        
        for (let i = 0; i < totalCount; i++) {
            const panel = document.createElement('div');
            panel.className = 'bg-panel';
            const coverIndex = i % uniqueCount % covers.length;
            panel.style.backgroundImage = `url("${covers[coverIndex]}")`;
            wall.appendChild(panel);
            setTimeout(() => panel.classList.add('fade-in'), Math.random() * 3000);
        }

        setInterval(() => {
            const panels = wall.children;
            if (panels.length < totalCount) return;
            
            for(let i=0; i<3; i++) {
                const randomIndex = Math.floor(Math.random() * 50); // limit to first 50 panels for performance
                const p1 = panels[randomIndex];
                const p2 = panels[randomIndex + uniqueCount];
                if (!p1 || !p2) continue;
                
                p1.style.opacity = '0';
                p2.style.opacity = '0';
                
                setTimeout(async () => {
                    try {
                        const r = await fetch(API_BASE + '/random');
                        const m = await r.json();
                        let src = m.coverUrl;
                        if (src && !src.includes('/api/manga/proxy-image')) {
                            src = `${API_BASE}/proxy-image?url=${encodeURIComponent(src)}`;
                        }
                        
                        const img = new Image();
                        img.src = src;
                        img.onload = () => {
                            p1.style.backgroundImage = `url("${src}")`;
                            p2.style.backgroundImage = `url("${src}")`;
                            p1.style.opacity = '1';
                            p2.style.opacity = '1';
                        };
                    } catch(e) {}
                }, 2000);
            }
        }, 15000);

    } catch (err) {
        console.warn('Manga wall init failed', err);
    }
}

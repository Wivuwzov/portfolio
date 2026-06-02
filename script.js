document.addEventListener('DOMContentLoaded', () => {
    
    // === 1. ИНТЕРАКТИВНЫЕ ФУНКЦИИ (СВЕЧЕНИЕ И МАГНИТЫ) ===
    const glow = document.getElementById('cursor-glow');
    if (glow) {
        document.addEventListener('mousemove', (e) => {
            glow.style.left = e.clientX - 200 + 'px';
            glow.style.top = e.clientY - 200 + 'px';
        });
    }

    // Магнитный эффект для элементов управления
    document.querySelectorAll('.nav-arrow-btn, .tg-btn, #theme-toggle').forEach(btn => {
        btn.addEventListener('mousemove', (e) => {
            const rect = btn.getBoundingClientRect();
            const x = (e.clientX - rect.left - rect.width / 2) / 2;
            const y = (e.clientY - rect.top - rect.height / 2) / 2;
            btn.style.transform = `translate(${x}px, ${y}px)`;
        });
        btn.addEventListener('mouseleave', () => {
            btn.style.transform = `translate(0, 0)`;
        });
    });

    // === 2. ТЕМНАЯ ТЕМА ===
    const themeToggle = document.getElementById('theme-toggle');
    const currentTheme = localStorage.getItem('theme');
    
    if (currentTheme === 'dark') {
        document.documentElement.setAttribute('data-theme', 'dark');
        themeToggle.textContent = '☀️';
    } else {
        themeToggle.textContent = '🌙';
    }

    themeToggle.addEventListener('click', () => {
        let theme = document.documentElement.getAttribute('data-theme');
        if (theme === 'dark') {
            document.documentElement.removeAttribute('data-theme');
            localStorage.setItem('theme', 'light');
            themeToggle.textContent = '🌙'; 
        } else {
            document.documentElement.setAttribute('data-theme', 'dark');
            localStorage.setItem('theme', 'dark');
            themeToggle.textContent = '☀️';
        }
    });

    // === 3. ЛОГИКА СТРАНИЦ И ФИЛЬТРАЦИИ ===
    const tabButtons = document.querySelectorAll('.tab-btn');
    const portfolioItems = Array.from(document.querySelectorAll('.portfolio-item'));
    const prevBtn = document.getElementById('prev-page');
    const nextBtn = document.getElementById('next-page');
    const pageIndicator = document.getElementById('page-indicator');

    const ITEMS_PER_PAGE = 3; 
    let currentFilteredItems = [...portfolioItems]; 
    let currentPage = 1;

function updateGrid() {
        const totalItems = currentFilteredItems.length;
        const totalPages = Math.ceil(totalItems / ITEMS_PER_PAGE) || 1;
        
        if (currentPage > totalPages) currentPage = totalPages;
        if (currentPage < 1) currentPage = 1;

        const startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
        const endIndex = startIndex + ITEMS_PER_PAGE;

        // ЭТАП 1: Находим те карточки, которые СЕЙЧАС видны, и запускаем им анимацию закрытия (fade-out)
        const currentlyVisible = portfolioItems.filter(item => !item.classList.contains('hide'));
        
        currentlyVisible.forEach(item => {
            item.classList.remove('fade-in');
            item.classList.add('fade-out');
        });

        // Если на экране есть видимые карточки, ждем их красивого закрытия (300мс), если нет — переключаем сразу
        const delay = currentlyVisible.length > 0 ? 300 : 0;

        // ЭТАП 2: После того как старые карточки плавно закрылись, меняем контент страницы
        setTimeout(() => {
            portfolioItems.forEach(item => {
                item.classList.add('hide');
                item.classList.remove('fade-out');
                item.classList.remove('fade-in');
            });

            // Берем новые карточки для текущей страницы
            const visibleItems = currentFilteredItems.slice(startIndex, endIndex);
            
            // Показываем их в структуре документа (пока невидимыми)
            visibleItems.forEach(item => item.classList.remove('hide'));

            // ЭТАП 3: На следующем кадре запускаем им плавное появление (fade-in)
            setTimeout(() => {
                visibleItems.forEach(item => item.classList.add('fade-in'));
            }, 40);

            // Обновляем текст и состояние кнопок стрелочек
            pageIndicator.textContent = `${currentPage} / ${totalPages}`;
            prevBtn.disabled = currentPage === 1;
            nextBtn.disabled = currentPage === totalPages;

        }, delay); 
    }

    prevBtn.addEventListener('click', () => {
        if (currentPage > 1) {
            currentPage--;
            updateGrid();
        }
    });

    nextBtn.addEventListener('click', () => {
        const totalPages = Math.ceil(currentFilteredItems.length / ITEMS_PER_PAGE) || 1;
        if (currentPage < totalPages) {
            currentPage++;
            updateGrid();
        }
    });

    // Эффект 3D Наклона для БЕНТО-карточек
    portfolioItems.forEach(item => {
        item.addEventListener('mousemove', (e) => {
            const rect = item.getBoundingClientRect();
            const x = e.clientX - rect.left;
            const y = e.clientY - rect.top;
            const centerX = rect.width / 2;
            const centerY = rect.height / 2;
            const rotateX = (y - centerY) / 10;
            const rotateY = (centerX - x) / 10;
            item.style.transform = `perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) scale(1.02)`;
        });
        item.addEventListener('mouseleave', () => {
            item.style.transform = `perspective(1000px) rotateX(0) rotateY(0) scale(1)`;
        });
    });

    // Фильтрация по табам
    tabButtons.forEach(button => {
        button.addEventListener('click', (e) => {
            e.stopPropagation();
            if (button.classList.contains('active')) return;

            tabButtons.forEach(btn => btn.classList.remove('active'));
            button.classList.add('active');

            const targetCategory = button.getAttribute('data-target');

            if (targetCategory === 'all') {
                currentFilteredItems = [...portfolioItems];
            } else {
                currentFilteredItems = portfolioItems.filter(item => item.getAttribute('data-category') === targetCategory);
            }

            currentPage = 1;
            updateGrid();
        });
    });

    // Первичная инициализация первой страницы
    currentFilteredItems.forEach((item, index) => {
        if (index < ITEMS_PER_PAGE) {
            item.classList.remove('hide');
            item.classList.add('fade-in');
        } else {
            item.classList.add('hide');
        }
    });
    const initTotalPages = Math.ceil(currentFilteredItems.length / ITEMS_PER_PAGE) || 1;
    pageIndicator.textContent = `1 / ${initTotalPages}`;

    // === 4. МОДАЛЬНОЕ ОКНО С КВАДРАТНЫМ РЕЖИМОМ ===
    const modal = document.getElementById('portfolio-modal');
    const closeModalBtn = document.querySelector('.close-modal');
    const modalImg = document.getElementById('modal-img');
    const modalTitle = document.getElementById('modal-title');
    const modalTags = document.getElementById('modal-tags');
    const modalFullDesc = document.getElementById('modal-full-desc');
    const modalAction = document.getElementById('modal-action');

    portfolioItems.forEach(item => {
        item.addEventListener('click', (e) => {
            if (e.target.classList.contains('project-link-btn')) return;

            const imgElement = item.querySelector('img');
            const imgUrl = imgElement ? imgElement.src : '';
            const titleElement = item.querySelector('.item-title');
            const title = titleElement ? titleElement.textContent : 'Проект';
            const bentoTag = item.querySelector('.tag');
            const fullDetails = item.querySelector('.item-full-details');
            
            modalImg.src = imgUrl;
            modalTitle.textContent = title;
            
            if (bentoTag) {
                modalTags.innerHTML = `<span class="tag">${bentoTag.textContent}</span>`;
            } else {
                modalTags.innerHTML = '';
            }
            
            if (fullDetails) {
                const descParagraph = fullDetails.querySelector('p');
                const linkButton = fullDetails.querySelector('.project-link-btn');
                
                modalFullDesc.innerHTML = descParagraph ? descParagraph.innerHTML : 'Описание отсутствует.';
                modalAction.innerHTML = linkButton ? linkButton.outerHTML : '';
            } else {
                modalFullDesc.textContent = 'Подробное описание проекта отсутствует.';
                modalAction.innerHTML = '';
            }

            // ЛОГИКА: Если категория карточки "category2" (Скины), включаем квадратный вид
            const itemCategory = item.getAttribute('data-category');
            if (itemCategory === 'category2') {
                modal.classList.add('square-mode');
            } else {
                modal.classList.remove('square-mode');
            }

            modal.style.display = 'block';
            document.body.classList.add('modal-open');
        });
    });

    if (closeModalBtn) {
        closeModalBtn.addEventListener('click', closeModal);
    }
    window.addEventListener('click', (event) => { 
        if (event.target === modal) closeModal(); 
    });

    function closeModal() {
        modal.style.display = 'none';
        modal.classList.remove('square-mode'); // Обязательно сбрасываем класс скинов при закрытии
        document.body.classList.remove('modal-open');
    }
});
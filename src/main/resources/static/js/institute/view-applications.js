document.addEventListener('DOMContentLoaded', () => {
    const tabs = document.querySelectorAll('#shift-tabs .nav-link');
    const loading = document.getElementById('programme-table-loading');
    const errorEl = document.getElementById('programme-table-error');
    const wrap = document.getElementById('programme-table-wrap');
    const body = document.getElementById('programme-table-body');
    const emptyEl = document.getElementById('programme-table-empty');

    const escapeHtml = (str) => String(str ?? '').replace(/[&<>"']/g, (c) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));

    const loadShift = async (shift) => {
        loading.classList.remove('d-none');
        errorEl.classList.add('d-none');
        wrap.classList.add('d-none');
        emptyEl.classList.add('d-none');
        body.replaceChildren();

        try {
            const res = await axios.get('/api/institute/allotments/programme-summary', { params: { shift } });
            loading.classList.add('d-none');

            const rows = res.data || [];
            if (rows.length === 0) {
                emptyEl.classList.remove('d-none');
                return;
            }

            rows.forEach((row) => {
                const total = row.totalSeats ?? 0;
                const occupied = row.allottedCount ?? 0;
                const remaining = Math.max(0, total - occupied);

                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${escapeHtml(row.programmeName)}</td>
                    <td class="text-center">${total}</td>
                    <td class="text-center">
                        <span class="badge bg-success-subtle text-success-emphasis">${occupied}</span>
                    </td>
                    <td class="text-center">${remaining}</td>
                    <td class="text-center">
                        <a class="btn btn-sm btn-outline-primary" href="/institute/view-applications/${row.programmeOfferedId}">
                            <i class="bi bi-people-fill me-1"></i>View Applicants
                        </a>
                    </td>`;
                body.appendChild(tr);
            });

            wrap.classList.remove('d-none');
        } catch (err) {
            loading.classList.add('d-none');
            errorEl.textContent = err?.response?.data?.message || 'Unable to load programmes for this shift.';
            errorEl.classList.remove('d-none');
        }
    };

    if (tabs.length > 0) {
        tabs.forEach((tab) => {
            tab.addEventListener('click', () => {
                tabs.forEach((t) => t.classList.remove('active'));
                tab.classList.add('active');
                loadShift(tab.dataset.shift);
            });
        });
        loadShift(tabs[0].dataset.shift);
    } else {
        // No shift distinctions at this institute — default to DAY.
        loadShift('DAY');
    }
});
document.addEventListener('DOMContentLoaded', () => {
    const main = document.querySelector('main[data-programme-offered-id]');
    if (!main) return;
    const programmeOfferedId = main.dataset.programmeOfferedId;

    const loading = document.getElementById('applicants-loading');
    const errorEl = document.getElementById('applicants-error');
    const emptyEl = document.getElementById('applicants-empty');
    const wrap = document.getElementById('applicants-table-wrap');
    const body = document.getElementById('applicants-table-body');

    const escapeHtml = (str) => String(str ?? '').replace(/[&<>"']/g, (c) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));

    // ---------------------------------------------------------------
    // Load the applicant table (ACCEPTED = currently occupying a seat)
    // ---------------------------------------------------------------
    const loadApplicants = async () => {
        try {
            const res = await axios.get('/api/institute/allotments/allotments-by-programme', {
                params: { programmeOfferedId, status: 'ACCEPTED' }
            });
            loading.classList.add('d-none');

            const rows = res.data || [];
            if (rows.length === 0) {
                emptyEl.classList.remove('d-none');
                return;
            }

            rows.forEach((row) => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${escapeHtml(row.applicationNo)}</td>
                    <td>${escapeHtml(row.applicantName)}</td>
                    <td>${escapeHtml(row.allottedCategory || '-')}</td>
                    <td>${escapeHtml(row.roundAndPhase || '-')}</td>
                    <td class="text-center text-nowrap">
                        <button class="btn btn-sm btn-outline-secondary me-1 btn-view-details" data-allotment-id="${row.allotmentId}">
                            <i class="bi bi-person-lines-fill me-1"></i>Details
                        </button>
                        <button class="btn btn-sm btn-outline-secondary me-1 btn-view-application" data-allotment-id="${row.allotmentId}">
                            <i class="bi bi-file-earmark-text me-1"></i>Application
                        </button>
                        <button class="btn btn-sm btn-outline-secondary btn-view-documents" data-allotment-id="${row.allotmentId}">
                            <i class="bi bi-folder2-open me-1"></i>Documents
                        </button>
                    </td>`;
                body.appendChild(tr);
            });

            wrap.classList.remove('d-none');
        } catch (err) {
            loading.classList.add('d-none');
            errorEl.textContent = err?.response?.data?.message || 'Unable to load applicants.';
            errorEl.classList.remove('d-none');
        }
    };

    // ---------------------------------------------------------------
    // View Details: personal + academic, rendered read-only
    // ---------------------------------------------------------------
    const detailsModalEl = document.getElementById('details-modal');
    const detailsModal = new bootstrap.Modal(detailsModalEl);

    const humanizeKey = (key) => key
        .replace(/([a-z])([A-Z])/g, '$1 $2')
        .replace(/^./, (c) => c.toUpperCase());

    const renderKeyValueTable = (obj, tbody, skipKeys = []) => {
        tbody.replaceChildren();
        Object.entries(obj || {}).forEach(([key, value]) => {
            if (skipKeys.includes(key) || value === null || value === undefined) return;
            if (typeof value === 'object' && !Array.isArray(value)) return; // nested handled separately
            if (Array.isArray(value)) return;
            const tr = document.createElement('tr');
            tr.innerHTML = `<th class="text-muted small" style="width:40%;">${escapeHtml(humanizeKey(key))}</th>
                             <td>${escapeHtml(String(value))}</td>`;
            tbody.appendChild(tr);
        });
    };

    const renderAddressBlock = (title, addr) => {
        if (!addr) return '';
        const parts = ['line1', 'line2', 'townVillage', 'district', 'state', 'pincode', 'country']
            .map((f) => addr[f]).filter(Boolean).map(escapeHtml).join(', ');
        return `<div class="mt-3"><h6 class="text-muted">${title}</h6><p class="mb-0">${parts || '-'}</p></div>`;
    };

    const loadDetails = async (allotmentId) => {
        const personalLoading = document.getElementById('details-personal-loading');
        const personalTable = document.getElementById('details-personal-table');
        const personalTbody = personalTable.querySelector('tbody');
        const academicLoading = document.getElementById('details-academic-loading');
        const academicContent = document.getElementById('details-academic-content');

        personalLoading.classList.remove('d-none');
        personalTable.classList.add('d-none');
        academicLoading.classList.remove('d-none');
        academicContent.classList.add('d-none');

        detailsModal.show();

        try {
            const res = await axios.get(`/api/institute/allotments/${allotmentId}/personal-details`);
            const dto = res.data;
            renderKeyValueTable(dto, personalTbody, ['permanentAddress', 'communicationAddress']);
            let extra = renderAddressBlock('Permanent Address', dto.permanentAddress);
            extra += renderAddressBlock('Communication Address', dto.communicationAddress);
            if (extra) {
                const wrapper = document.createElement('div');
                wrapper.innerHTML = extra;
                personalTbody.parentElement.after(wrapper);
            }
            personalLoading.classList.add('d-none');
            personalTable.classList.remove('d-none');
        } catch (err) {
            personalLoading.innerHTML = `<p class="text-danger mb-0">${escapeHtml(err?.response?.data?.message || 'Unable to load personal details.')}</p>`;
        }

        try {
            const res = await axios.get(`/api/institute/allotments/${allotmentId}/academic-details`);
            const dto = res.data;

            let html = '';
            (dto.latestRecords || []).forEach((rec) => {
                html += `<h6 class="text-muted mt-2">${escapeHtml(rec.qualificationLevel || 'Qualification')}</h6>
                         <p class="mb-1">Board/University: ${escapeHtml(rec.boardOrUniversity || '-')} &middot;
                            Year: ${escapeHtml(rec.yearOfPassing || '-')} &middot;
                            Percentage: ${escapeHtml(rec.percentage ?? '-')}</p>`;
            });
            if (dto.provideCuetScores && dto.cuetScore) {
                html += `<h6 class="text-muted mt-3">CUET Score</h6>
                         <p class="mb-1">NTA Score: ${escapeHtml(dto.cuetScore.ntaScore ?? '-')}</p>`;
            }
            if (dto.provideJeeScores && dto.jeeScore) {
                html += `<h6 class="text-muted mt-3">JEE Score</h6><p class="mb-1">Score: ${escapeHtml(dto.jeeScore.score ?? '-')}</p>`;
            }
            if (!html) html = '<p class="text-muted mb-0">No academic records on file.</p>';

            academicContent.innerHTML = html;
            academicLoading.classList.add('d-none');
            academicContent.classList.remove('d-none');
        } catch (err) {
            academicLoading.innerHTML = `<p class="text-danger mb-0">${escapeHtml(err?.response?.data?.message || 'Unable to load academic details.')}</p>`;
        }
    };

    // ---------------------------------------------------------------
    // View Application: read-only PDF, view + download
    // ---------------------------------------------------------------
    const applicationModal = new bootstrap.Modal(document.getElementById('application-modal'));
    const openApplication = (allotmentId) => {
        document.getElementById('application-pdf-frame').src =
            `/api/institute/allotments/${allotmentId}/application-pdf?mode=inline`;
        document.getElementById('application-download-link').href =
            `/api/institute/allotments/${allotmentId}/application-pdf?mode=download`;
        applicationModal.show();
    };

    // ---------------------------------------------------------------
    // View Documents: reuses the existing read-only document-review fragment
    // ---------------------------------------------------------------
    const documentsModal = new bootstrap.Modal(document.getElementById('documents-modal'));
    const docViewerModal = new bootstrap.Modal(document.getElementById('doc-viewer-modal'));

    const openDocuments = async (allotmentId) => {
        const docLoading = document.getElementById('documents-loading');
        const docContent = document.getElementById('documents-content');
        docLoading.classList.remove('d-none');
        docContent.replaceChildren();
        documentsModal.show();

        try {
            const res = await axios.get(`/api/institute/allotments/${allotmentId}/document-review`);
            docLoading.classList.add('d-none');

            const parser = new DOMParser();
            const doc = parser.parseFromString(res.data, 'text/html');
            doc.querySelectorAll('script, iframe, object, embed').forEach((el) => el.remove());
            doc.querySelectorAll('*').forEach((el) => {
                [...el.attributes].forEach((attr) => {
                    if (attr.name.startsWith('on')) el.removeAttribute(attr.name);
                });
            });

            docContent.innerHTML = doc.body.innerHTML;

            // Wire up click-to-view on every uploaded (clickable) document preview
            docContent.querySelectorAll('.clickable-preview').forEach((box) => {
                box.style.cursor = 'pointer';
                box.addEventListener('click', () => {
                    const docId = box.dataset.documentId;
                    if (!docId) return;
                    const isImg = box.querySelector('img') !== null;
                    const imgEl = document.getElementById('doc-viewer-image');
                    const pdfEl = document.getElementById('doc-viewer-pdf');
                    if (isImg) {
                        imgEl.src = `/applicants/documents/${docId}`;
                        imgEl.classList.remove('d-none');
                        pdfEl.classList.add('d-none');
                        pdfEl.src = '';
                    } else {
                        pdfEl.src = `/applicants/documents/${docId}`;
                        pdfEl.classList.remove('d-none');
                        imgEl.classList.add('d-none');
                        imgEl.src = '';
                    }
                    docViewerModal.show();
                });
            });
        } catch (err) {
            docLoading.classList.add('d-none');
            docContent.innerHTML = `<p class="text-danger mb-0">${escapeHtml(err?.response?.data?.message || 'Unable to load documents.')}</p>`;
        }
    };

    // ---------------------------------------------------------------
    // Delegate action button clicks
    // ---------------------------------------------------------------
    body.addEventListener('click', (e) => {
        const detailsBtn = e.target.closest('.btn-view-details');
        if (detailsBtn) return loadDetails(detailsBtn.dataset.allotmentId);

        const appBtn = e.target.closest('.btn-view-application');
        if (appBtn) return openApplication(appBtn.dataset.allotmentId);

        const docsBtn = e.target.closest('.btn-view-documents');
        if (docsBtn) return openDocuments(docsBtn.dataset.allotmentId);
    });

    loadApplicants();
});
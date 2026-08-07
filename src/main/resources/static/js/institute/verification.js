document.addEventListener('DOMContentLoaded', function () {
    console.log("Verification Dashboard Loaded");

    // --- 1. Configuration & State ---
    const PAGE_SIZE = 10;
    const state = {
        PENDING: { page: 0, statuses: 'PENDING_VERIFICATION', tbody: 'pending-table-body', pag: 'pending-pagination', stats: 'stats-pending' },
        VERIFIED: { page: 0, statuses: 'PENDING,ACCEPTED', tbody: 'verified-table-body', pag: 'verified-pagination', stats: 'stats-verified' },
        REJECTED: { page: 0, statuses: 'INSTITUTE_REJECTED,REJECTED', tbody: 'rejected-table-body', pag: 'rejected-pagination', stats: 'stats-rejected' }
    };

    let currentAllotmentId = null;

    // --- 2. Modal Initialization ---
    const rejectionModal = new bootstrap.Modal(document.getElementById('rejectionModal'));
    const verificationModal = new bootstrap.Modal(document.getElementById('verificationConfirmModal'));
    const docListModal = new bootstrap.Modal(document.getElementById('documentReviewModal'));
    const viewerModal = new bootstrap.Modal(document.getElementById('documentViewerModal'));

    const fullResImage = document.getElementById('full-res-image');
    const pdfFrame = document.getElementById('pdf-viewer-frame');
    const imgContainer = document.getElementById('image-viewer-container');
    const viewerTitle = document.getElementById('viewer-filename');

    // --- 3. Data Loading Logic ---
    const loadData = async (tabKey, page = 0) => {
        const config = state[tabKey];
        config.page = page;

        const tbody = document.getElementById(config.tbody);
        const pagDiv = document.getElementById(config.pag);
        const statsEl = document.getElementById(config.stats);

        const programmeFilter = document.getElementById('programmeFilter');
        const selectedProgId = programmeFilter ? programmeFilter.value : "";

        const shiftFilter = document.getElementById('shiftFilter');
        const selectedShift = shiftFilter ? shiftFilter.value : "";

        const routeFilter = document.getElementById('routeFilter');
        const selectedRoute = routeFilter ? routeFilter.value : "";

        tbody.replaceChildren();
        const tr = document.createElement("tr");
        const td = document.createElement("td");
        td.colSpan = 5;
        td.className = "text-center py-4";

        const spinner = document.createElement("div");
        spinner.className = "spinner-border text-primary spinner-border-sm";

        td.appendChild(spinner);
        tr.appendChild(td);
        tbody.appendChild(tr);

        try {
            const requestParams = { page: page, size: PAGE_SIZE, statuses: config.statuses };
            if (selectedProgId) requestParams.programmeId = selectedProgId;
            if (selectedShift) requestParams.shift = selectedShift;
            if (selectedRoute) requestParams.admissionRoute = selectedRoute;

            const res = await axios.get(`/api/institute/allotments/paged`, { params: requestParams });

            const paged = res.data;
            renderTable(tbody, paged.data, tabKey);
            renderPagination(pagDiv, paged, tabKey);
            if (statsEl) statsEl.textContent = paged.totalElements;

        } catch (err) {
            console.error("Fetch Error:", err);
            tbody.replaceChildren();
            const trErr = document.createElement("tr");
            const tdErr = document.createElement("td");
            tdErr.colSpan = 5;
            tdErr.className = "text-center text-danger";
            tdErr.textContent = "Failed to load data.";
            trErr.appendChild(tdErr);
            tbody.appendChild(trErr);
        }
    };

    const renderTable = (tbody, items, tabKey) => {
        tbody.replaceChildren();

        if (!items || items.length === 0) {
            const tr = document.createElement("tr");
            const td = document.createElement("td");
            td.colSpan = 5;
            td.className = "text-center py-5 text-muted";
            td.textContent = `No ${tabKey.toLowerCase()} records found.`;
            tr.appendChild(td);
            tbody.appendChild(tr);
            return;
        }

        items.forEach((item, idx) => {
            const rowNum = (state[tabKey].page * PAGE_SIZE) + (idx + 1);

            const tr = document.createElement("tr");

            const td1 = document.createElement("td");
            td1.className = "text-muted small";
            td1.textContent = rowNum;

            const td2 = document.createElement("td");
            const nameDiv = document.createElement("div");
            nameDiv.className = "fw-bold text-dark";
            nameDiv.textContent = item.applicantName;
            const small = document.createElement("small");
            small.className = "text-muted";
            small.textContent = item.applicationNo;
            td2.append(nameDiv, small);

            const td3 = document.createElement("td");
            const prog = document.createElement("div");
            prog.textContent = item.programmeName;
            const phase = document.createElement("small");
            phase.className = "text-info";
            phase.textContent = item.roundAndPhase;
            td3.append(prog, phase);

            const td4 = document.createElement("td");
            if (tabKey === 'REJECTED') {
                const span = document.createElement("span");
                span.className = "text-danger small fw-bold";
                span.textContent = item.remarks || "No Reason";
                td4.appendChild(span);
            } else {
                const span = document.createElement("span");
                span.className = "badge bg-secondary";
                span.textContent = item.allottedCategory || "OPEN";
                td4.appendChild(span);
            }

            const td5 = document.createElement("td");
            td5.className = "text-center";

            if (tabKey === 'PENDING') {
                const group = document.createElement("div");
                group.className = "btn-group";

                const btnView = createBtn("btn-outline-primary", "bi bi-eye-fill", item);
                btnView.classList.add("view-docs-btn");
                btnView.title = "View Documents";

                const btnAppView = createBtn("btn-outline-secondary", "bi bi-file-earmark-text-fill", item);
                btnAppView.classList.add("view-application-btn");
                btnAppView.title = "View Application";

                const btnAppDownload = createBtn("btn-outline-secondary", "bi bi-download", item);
                btnAppDownload.classList.add("download-application-btn");
                btnAppDownload.title = "Download Application";

                const btnEdit = createBtn("btn-outline-warning", "bi bi-pencil-fill", item);
                btnEdit.classList.add("edit-applicant-btn");
                btnEdit.title = "Edit Applicant Details";

                const btnHistory = createBtn("btn-outline-info", "bi bi-clock-history", item);
                btnHistory.classList.add("view-history-btn");
                btnHistory.title = "Verification & Edit History";

                const btnVerify = createBtn("btn-outline-success", "bi bi-check-circle-fill", item);
                btnVerify.classList.add("verify-btn");

                const btnReject = createBtn("btn-outline-danger", "bi bi-x-circle-fill", item);
                btnReject.classList.add("reject-btn");

                group.append(btnView, btnAppView, btnAppDownload, btnEdit, btnHistory, btnVerify, btnReject);
                td5.appendChild(group);

            } else {
                const group = document.createElement("div");
                group.className = "btn-group";

                const btn = createBtn("btn-light text-primary", "bi bi-eye-fill", item);
                btn.classList.add("view-docs-btn");
                btn.title = "View Documents";

                const btnAppView = createBtn("btn-light text-secondary", "bi bi-file-earmark-text-fill", item);
                btnAppView.classList.add("view-application-btn");
                btnAppView.title = "View Application";

                const btnAppDownload = createBtn("btn-light text-secondary", "bi bi-download", item);
                btnAppDownload.classList.add("download-application-btn");
                btnAppDownload.title = "Download Application";

                const btnHistory = createBtn("btn-light text-info", "bi bi-clock-history", item);
                btnHistory.classList.add("view-history-btn");
                btnHistory.title = "Verification & Edit History";

                group.append(btn, btnAppView, btnAppDownload, btnHistory);
                td5.appendChild(group);
            }

            tr.append(td1, td2, td3, td4, td5);
            tbody.appendChild(tr);
        });
    };

    function createBtn(className, iconClass, item) {
        const btn = document.createElement("button");
        btn.className = `btn btn-sm ${className} border-0`;
        btn.dataset.id = item.allotmentId;
        btn.dataset.name = item.applicantName;
        btn.dataset.applicantId = item.applicantId;
        const icon = document.createElement("i");
        icon.className = iconClass;
        btn.appendChild(icon);
        return btn;
    }

    const renderPagination = (container, paged, tabKey) => {
        container.replaceChildren();
        if (paged.totalPages <= 1) return;

        const wrapper = document.createElement("div");
        wrapper.className = "d-flex justify-content-between align-items-center p-3 border-top bg-light";

        const info = document.createElement("span");
        info.className = "small text-muted";
        info.textContent = `Showing page ${paged.page + 1} of ${paged.totalPages}`;

        const nav = document.createElement("nav");
        const ul = document.createElement("ul");
        ul.className = "pagination pagination-sm mb-0";

        const prev = createPageBtn("Previous", paged.page === 0, paged.page - 1, tabKey);
        const next = createPageBtn("Next", paged.last, paged.page + 1, tabKey);

        ul.append(prev, next);
        nav.appendChild(ul);
        wrapper.append(info, nav);
        container.appendChild(wrapper);
    };

    function createPageBtn(text, disabled, page, tabKey) {
        const li = document.createElement("li");
        li.className = `page-item ${disabled ? 'disabled' : ''}`;
        const btn = document.createElement("button");
        btn.className = "page-link";
        btn.textContent = text;
        btn.dataset.page = page;
        btn.dataset.tab = tabKey;
        li.appendChild(btn);
        return li;
    }

    // --- 4. Event Delegation ---
    document.addEventListener('click', async (e) => {
        const btn = e.target.closest('button');
        if (!btn) return;

        const id = btn.dataset.id || btn.dataset.allotmentId;
        const name = btn.dataset.name || btn.dataset.applicantName;

        if (btn.classList.contains('page-link')) {
            const tab = btn.dataset.tab;
            const page = parseInt(btn.dataset.page);
            loadData(tab, page);
        }

        if (btn.classList.contains('view-docs-btn')) viewDocuments(id, name);

        if (btn.classList.contains('view-application-btn')) {
            window.open(`/api/institute/allotments/${id}/application-pdf?mode=inline`, '_blank');
        }

        if (btn.classList.contains('download-application-btn')) {
            window.location.href = `/api/institute/allotments/${id}/application-pdf?mode=download`;
        }

        if (btn.classList.contains('edit-applicant-btn')) openEditModal(id, name);

        if (btn.classList.contains('view-history-btn')) openHistoryModal(btn.dataset.applicantId, name);

        if (btn.classList.contains('verify-btn')) {
            currentAllotmentId = id;
            document.getElementById('verify-applicant-name').textContent = name;
            verificationModal.show();
        }

        if (btn.classList.contains('reject-btn')) {
            currentAllotmentId = id;
            document.getElementById('reject-applicant-name').textContent = name;
            document.getElementById('rejectionRemarks').value = '';
            rejectionModal.show();
        }
    });

    // --- STAT CARDS: fetched independently of which tab is open, so all three
    // cards show correct numbers immediately instead of only after their tab
    // has been clicked at least once. ---
    const loadStats = async () => {
        const programmeFilter = document.getElementById('programmeFilter');
        const selectedProgId = programmeFilter ? programmeFilter.value : "";

        const shiftFilter = document.getElementById('shiftFilter');
        const selectedShift = shiftFilter ? shiftFilter.value : "";

        const routeFilter = document.getElementById('routeFilter');
        const selectedRoute = routeFilter ? routeFilter.value : "";

        const requestParams = {};
        if (selectedProgId) requestParams.programmeId = selectedProgId;
        if (selectedShift) requestParams.shift = selectedShift;
        if (selectedRoute) requestParams.admissionRoute = selectedRoute;

        try {
            const res = await axios.get(`/api/institute/allotments/counts`, { params: requestParams });
            const counts = res.data || {};
            document.getElementById('stats-pending').textContent = counts.PENDING ?? 0;
            document.getElementById('stats-verified').textContent = counts.VERIFIED ?? 0;
            document.getElementById('stats-rejected').textContent = counts.REJECTED ?? 0;
        } catch (err) {
            console.error("Failed to load stat counts:", err);
        }
    };

    // --- FILTER CHANGE LISTENER ---
    const resetAndReload = () => {
        state.PENDING.page = 0;
        state.VERIFIED.page = 0;
        state.REJECTED.page = 0;
        const activeTabBtn = document.querySelector('.nav-link.active');
        if (activeTabBtn) {
            const key = activeTabBtn.id.replace('tab-', '').replace('-btn', '').toUpperCase();
            loadData(key, 0);
        }
        loadStats();
    };

    const programmeFilterElement = document.getElementById('programmeFilter');
    if (programmeFilterElement) programmeFilterElement.addEventListener('change', resetAndReload);

    const shiftFilterElement = document.getElementById('shiftFilter');
    if (shiftFilterElement) shiftFilterElement.addEventListener('change', resetAndReload);

    const routeFilterElement = document.getElementById('routeFilter');
    if (routeFilterElement) routeFilterElement.addEventListener('change', resetAndReload);

    // --- 5. Support Functions ---
    const showAlert = (title, message) => { alert(title + ": " + message); };

    const performAction = async (id, payload) => {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        try {
            await axios.post(`/api/institute/allotments/${id}/verify`, payload, {
                headers: { [header]: token }
            });
            showAlert('Success', 'Action recorded successfully.');
            rejectionModal.hide();
            verificationModal.hide();
            loadData('PENDING', state.PENDING.page);
            loadData('VERIFIED', 0);
            loadData('REJECTED', 0);
            loadStats();
        } catch (err) {
            showAlert('Error', 'Action failed to process.');
        }
    };

    const csrfHeaders = () => {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        return { [header]: token };
    };

    // --- 6. Edit Applicant Modal ---
    const editModal = new bootstrap.Modal(document.getElementById('editApplicantModal'));
    let currentEditAllotmentId = null;

    const openEditModal = async (id, name) => {
        currentEditAllotmentId = id;
        document.getElementById('edit-applicant-name').textContent = name;

        const loadingEl = document.getElementById('edit-loading');
        const contentEl = document.getElementById('edit-tab-content');
        loadingEl.classList.remove('d-none');
        contentEl.classList.add('d-none');

        // Reset documents tab state
        document.getElementById('doc-tab-loading').classList.remove('d-none');
        document.getElementById('doc-tab-content').classList.add('d-none');
        document.getElementById('doc-tab-error').classList.add('d-none');
        document.getElementById('doc-replace-panel').classList.add('d-none');
        document.getElementById('doc-replace-status').textContent = '';

        // Reset to Personal tab and show only personal save button
        document.getElementById('edit-tab-personal-btn').click();
        document.getElementById('save-personal-btn').classList.remove('d-none');
        document.getElementById('save-academic-btn').classList.add('d-none');
        document.getElementById('save-entrance-btn').classList.add('d-none');

        // Tab switch: show/hide correct save button & lazy-load documents tab
        document.querySelectorAll('#editTabs button[data-bs-toggle="tab"]').forEach(tabBtn => {
            tabBtn.addEventListener('shown.bs.tab', (e) => {
                const activeId = e.target.id;
                document.getElementById('save-personal-btn').classList.toggle('d-none', activeId !== 'edit-tab-personal-btn');
                document.getElementById('save-academic-btn').classList.toggle('d-none', activeId !== 'edit-tab-academic-btn');
                document.getElementById('save-entrance-btn').classList.toggle('d-none', activeId !== 'edit-tab-entrance-btn');

                // Lazy-load documents when the Documents tab is first activated
                if (activeId === 'edit-tab-documents-btn') {
                    loadDocumentsTab(currentEditAllotmentId);
                }
            });
        });

        editModal.show();

        try {
            const subjectsPromise = allSubjectsCache
                ? Promise.resolve({ data: allSubjectsCache })
                : axios.get('/subject-data');

            const cuetPapersPromise = allCuetPapersCache
                ? Promise.resolve({ data: allCuetPapersCache })
                : axios.get('/api/institute/allotments/cuet-papers');

            const [personalRes, academicRes, entranceRes, subjectsRes, cuetPapersRes] = await Promise.all([
                axios.get(`/api/institute/allotments/${id}/personal-details`),
                axios.get(`/api/institute/allotments/${id}/academic-details`),
                axios.get(`/api/institute/allotments/${id}/entrance-details`),
                subjectsPromise,
                cuetPapersPromise
            ]);

            allSubjectsCache = subjectsRes.data || [];
            allCuetPapersCache = cuetPapersRes.data || [];

            populatePersonalForm(personalRes.data);
            populateAcademicSection(academicRes.data);
            populateEntranceSection(entranceRes.data);

            loadingEl.classList.add('d-none');
            contentEl.classList.remove('d-none');
        } catch (err) {
            console.error('Failed to load applicant details', err);
            loadingEl.classList.add('d-none');
            const msg = err?.response?.data?.message || 'Failed to load applicant details.';
            showAlert('Error', msg);
            editModal.hide();
        }
    };

    const populatePersonalForm = (dto) => {
        const form = document.getElementById('edit-personal-form');
        form.reset();
        const set = (name, value) => {
            const field = form.elements[name];
            if (field) field.value = value ?? '';
        };
        set('firstName', dto.firstName);
        set('middleName', dto.middleName);
        set('lastName', dto.lastName);
        set('dateOfBirth', dto.dateOfBirth);
        set('phoneNumber', dto.phoneNumber);
        set('email', dto.email);
        set('fatherName', dto.fatherName);
        set('motherName', dto.motherName);
        set('guardianName', dto.guardianName);

        const perm = dto.permanentAddress || {};
        set('permAddressLine1', perm.addressLine1);
        set('permAddressLine2', perm.addressLine2);
        set('permTownVillage', perm.townVillage);
        set('permPincode', perm.pincode);

        form.dataset.original = JSON.stringify(dto);
    };

    const buildPersonalPayload = () => {
        const form = document.getElementById('edit-personal-form');
        const original = JSON.parse(form.dataset.original || '{}');
        const fd = new FormData(form);

        const payload = { ...original };
        payload.firstName = fd.get('firstName');
        payload.middleName = fd.get('middleName');
        payload.lastName = fd.get('lastName');
        payload.dateOfBirth = fd.get('dateOfBirth');
        payload.phoneNumber = fd.get('phoneNumber');
        payload.email = fd.get('email');
        payload.fatherName = fd.get('fatherName');
        payload.motherName = fd.get('motherName');
        payload.guardianName = fd.get('guardianName');

        const line1 = fd.get('permAddressLine1');
        const line2 = fd.get('permAddressLine2');
        const town = fd.get('permTownVillage');
        const pincode = fd.get('permPincode');
        if (original.permanentAddress && original.permanentAddress.stateCode && original.permanentAddress.districtCode) {
            payload.permanentAddress = {
                ...original.permanentAddress,
                addressLine1: line1,
                addressLine2: line2,
                townVillage: town,
                pincode: pincode
            };
        } else {
            payload.permanentAddress = null;
        }
        payload.communicationAddress = original.communicationAddress || null;
        return payload;
    };

    document.getElementById('save-personal-btn').addEventListener('click', async () => {
        if (!currentEditAllotmentId) return;
        const form = document.getElementById('edit-personal-form');
        if (!form.reportValidity()) return;
        const payload = buildPersonalPayload();
        try {
            await axios.post(`/api/institute/allotments/${currentEditAllotmentId}/personal-details`, payload, {
                headers: csrfHeaders()
            });
            showAlert('Success', 'Personal details updated successfully.');
        } catch (err) {
            const msg = err?.response?.data?.message || 'Failed to save personal details.';
            showAlert('Error', msg);
        }
    });

    // --- Academic tab ---
    let currentAcademicDto = null;
    let allSubjectsCache = null;
    let allCuetPapersCache = null;
    const SUBJECT_MARKS_ELIGIBLE_QUALIFICATIONS = ['Class XII', 'Diploma', 'Bachelor', 'Master'];

    const populateAcademicSection = (dto) => {
        currentAcademicDto = dto || { latestRecords: [], pastRecords: [] };
        const container = document.getElementById('academic-records-container');
        container.replaceChildren();

        const renderGroup = (title, records, keyName) => {
            const heading = document.createElement('h6');
            heading.className = 'fw-bold text-muted mt-2';
            heading.textContent = title;
            container.appendChild(heading);

            if (!records || records.length === 0) {
                const p = document.createElement('p');
                p.className = 'text-muted small';
                p.textContent = 'No records.';
                container.appendChild(p);
                return;
            }

            const mk = (field, label, colClass, type = 'text') => {
                const col = document.createElement('div');
                col.className = colClass;
                const lbl = document.createElement('label');
                lbl.className = 'form-label small mb-0';
                lbl.textContent = label;
                const input = document.createElement('input');
                input.type = type;
                input.className = 'form-control form-control-sm academic-field';
                input.dataset.field = field;
                col.append(lbl, input);
                return { col, input };
            };

            const isSubjectMarksEligible = (qualificationValue) => {
                const val = (qualificationValue || '').trim();
                return val && SUBJECT_MARKS_ELIGIBLE_QUALIFICATIONS.some(term => val.includes(term));
            };

            // Renders the add/remove/edit rows of subject-wise marks for one record.
            const renderSubjectMarksSection = (sectionEl, rec) => {
                sectionEl.replaceChildren();
                if (!rec.subjectMarks) rec.subjectMarks = [];

                const subHeading = document.createElement('div');
                subHeading.className = 'small fw-bold text-muted mb-1 mt-1';
                subHeading.textContent = 'Subject-wise Marks';
                sectionEl.appendChild(subHeading);

                const rowsWrapper = document.createElement('div');
                rowsWrapper.className = 'subject-marks-rows';
                sectionEl.appendChild(rowsWrapper);

                const renderRows = () => {
                    rowsWrapper.replaceChildren();

                    if (rec.subjectMarks.length === 0) {
                        const p = document.createElement('p');
                        p.className = 'text-muted small mb-1';
                        p.textContent = 'No subjects added.';
                        rowsWrapper.appendChild(p);
                    }

                    rec.subjectMarks.forEach((sm, sIdx) => {
                        const sRow = document.createElement('div');
                        sRow.className = 'row g-2 mb-1 align-items-center subject-mark-edit-row';

                        const nameCol = document.createElement('div');
                        nameCol.className = 'col-md-5';
                        const select = document.createElement('select');
                        select.className = 'form-select form-select-sm subject-name-select';
                        const blankOpt = document.createElement('option');
                        blankOpt.value = '';
                        blankOpt.textContent = '-- Select Subject --';
                        select.appendChild(blankOpt);
                        (allSubjectsCache || []).forEach(s => {
                            const opt = document.createElement('option');
                            opt.value = s.subjectId;
                            opt.textContent = s.subjectName;
                            if (sm.subjectId != null && String(sm.subjectId) === String(s.subjectId)) {
                                opt.selected = true;
                            }
                            select.appendChild(opt);
                        });
                        select.addEventListener('change', () => {
                            const chosen = (allSubjectsCache || []).find(s => String(s.subjectId) === select.value);
                            sm.subjectId = chosen ? chosen.subjectId : null;
                            sm.subjectName = chosen ? chosen.subjectName : '';
                        });
                        nameCol.appendChild(select);

                        const marksCol = document.createElement('div');
                        marksCol.className = 'col-md-3';
                        const marksInput = document.createElement('input');
                        marksInput.type = 'number';
                        marksInput.className = 'form-control form-control-sm subject-marks-obtained';
                        marksInput.placeholder = 'Marks Obtained';
                        marksInput.value = sm.marksObtained ?? '';
                        marksInput.addEventListener('input', () => {
                            sm.marksObtained = marksInput.value === '' ? null : parseFloat(marksInput.value);
                        });
                        marksCol.appendChild(marksInput);

                        const totalCol = document.createElement('div');
                        totalCol.className = 'col-md-3';
                        const totalInput = document.createElement('input');
                        totalInput.type = 'number';
                        totalInput.className = 'form-control form-control-sm subject-marks-total';
                        totalInput.placeholder = 'Total Marks';
                        totalInput.value = sm.totalMarks ?? '';
                        totalInput.addEventListener('input', () => {
                            sm.totalMarks = totalInput.value === '' ? null : parseFloat(totalInput.value);
                        });
                        totalCol.appendChild(totalInput);

                        const removeCol = document.createElement('div');
                        removeCol.className = 'col-md-1 text-end';
                        const removeBtn = document.createElement('button');
                        removeBtn.type = 'button';
                        removeBtn.className = 'btn btn-sm btn-outline-danger';
                        removeBtn.innerHTML = '<i class="bi bi-trash"></i>';
                        removeBtn.addEventListener('click', () => {
                            rec.subjectMarks.splice(sIdx, 1);
                            renderRows();
                        });
                        removeCol.appendChild(removeBtn);

                        sRow.append(nameCol, marksCol, totalCol, removeCol);
                        rowsWrapper.appendChild(sRow);
                    });
                };
                renderRows();

                const addBtn = document.createElement('button');
                addBtn.type = 'button';
                addBtn.className = 'btn btn-sm btn-outline-primary mt-1 mb-2';
                addBtn.innerHTML = '<i class="bi bi-plus-circle me-1"></i>Add Subject';
                addBtn.addEventListener('click', () => {
                    rec.subjectMarks.push({ subjectId: null, subjectName: '', marksObtained: null, totalMarks: null });
                    renderRows();
                });
                sectionEl.appendChild(addBtn);
            };

            records.forEach((rec, idx) => {
                const row = document.createElement('div');
                row.className = 'row g-2 mb-2 align-items-center border-bottom pb-2';
                row.dataset.group = keyName;
                row.dataset.index = idx;

                const qualField = mk('qualificationLevel', 'Qualification', 'col-md-3');
                const fields = [
                    qualField,
                    mk('boardOrUniversity', 'Board/University', 'col-md-3'),
                    mk('schoolOrCollege', 'School/College', 'col-md-3'),
                    mk('streamOrMajor', 'Stream/Major', 'col-md-2'),
                    mk('percentage', '%', 'col-md-1', 'number')
                ];
                fields.forEach(f => {
                    f.input.value = rec[f.input.dataset.field] ?? '';
                    row.appendChild(f.col);
                });
                container.appendChild(row);

                // Subject-wise marks are only meaningful (and only stored server-side)
                // for the LATEST qualification record — mirrors applicant-side behavior.
                if (keyName === 'latestRecords') {
                    const subjectSection = document.createElement('div');
                    subjectSection.className = 'ms-3 mb-3 subject-marks-section';
                    container.appendChild(subjectSection);

                    const refreshSubjectSection = () => {
                        if (isSubjectMarksEligible(qualField.input.value)) {
                            subjectSection.classList.remove('d-none');
                            renderSubjectMarksSection(subjectSection, rec);
                        } else {
                            subjectSection.classList.add('d-none');
                            subjectSection.replaceChildren();
                        }
                    };

                    qualField.input.addEventListener('input', refreshSubjectSection);
                    refreshSubjectSection();
                }
            });
        };

        renderGroup('Latest Qualification', dto.latestRecords, 'latestRecords');
        renderGroup('Earlier Qualifications', dto.pastRecords, 'pastRecords');
    };

    document.getElementById('save-academic-btn').addEventListener('click', async () => {
        if (!currentEditAllotmentId || !currentAcademicDto) return;

        document.querySelectorAll('#academic-records-container [data-group]').forEach(row => {
            const group = row.dataset.group;
            const index = parseInt(row.dataset.index);
            const target = currentAcademicDto[group][index];
            row.querySelectorAll('.academic-field').forEach(input => {
                const field = input.dataset.field;
                target[field] = field === 'percentage' ? parseFloat(input.value) : input.value;
            });
        });
        // Note: subjectMarks entries are kept in sync with currentAcademicDto
        // directly via their own change listeners (see renderSubjectMarksSection),
        // so no extra DOM-read pass is needed for them here.

        try {
            await axios.post(`/api/institute/allotments/${currentEditAllotmentId}/academic-details`, currentAcademicDto, {
                headers: csrfHeaders()
            });
            showAlert('Success', 'Academic details updated successfully.');
        } catch (err) {
            const msg = err?.response?.data?.message || 'Failed to save academic details.';
            showAlert('Error', msg);
        }
    });

    // --- Entrance tab (CUET only) ---
    let currentEntranceDto = null;

    const populateEntranceSection = (dto) => {
        currentEntranceDto = dto || {};
        const container = document.getElementById('entrance-scores-container');
        container.replaceChildren();

        const cuet = dto?.cuetScore;
        if (!cuet) {
            container.innerHTML = '<p class="text-muted small">No CUET score on file for this applicant.</p>';
            return;
        }

        const topRow = document.createElement('div');
        topRow.className = 'row g-2 mb-3';

        [
            { field: 'applicationNumber', label: 'Application Number' },
            { field: 'rollNumber',        label: 'Roll Number' },
            { field: 'yearOfExam',        label: 'Year of Exam', type: 'number' }
        ].forEach(({ field, label, type = 'text' }) => {
            const col = document.createElement('div');
            col.className = 'col-md-4';
            const lbl = document.createElement('label');
            lbl.className = 'form-label small mb-0';
            lbl.textContent = label;
            const input = document.createElement('input');
            input.type = type;
            input.className = 'form-control form-control-sm cuet-top-field';
            input.dataset.field = field;
            input.value = cuet[field] ?? '';
            col.append(lbl, input);
            topRow.appendChild(col);
        });
        container.appendChild(topRow);

        const subHeading = document.createElement('h6');
        subHeading.className = 'fw-bold text-muted mt-3 mb-2';
        subHeading.textContent = 'CUET Subject Scores';
        container.appendChild(subHeading);

        // Master paper-name datalist (shared across all subject rows in this tab)
        const datalistId = 'cuet-papers-datalist';
        let datalist = document.getElementById(datalistId);
        if (!datalist) {
            datalist = document.createElement('datalist');
            datalist.id = datalistId;
            container.appendChild(datalist);
        }
        datalist.replaceChildren();
        (allCuetPapersCache || []).forEach(p => {
            const opt = document.createElement('option');
            opt.value = p.paperName;
            datalist.appendChild(opt);
        });

        const subjectsWrapper = document.createElement('div');
        subjectsWrapper.id = 'cuet-subjects-edit-wrapper';
        container.appendChild(subjectsWrapper);

        const renderSubjectRows = (subjects) => {
            subjectsWrapper.replaceChildren();
            (subjects || []).forEach((sub, idx) => {
                const row = document.createElement('div');
                row.className = 'row g-2 mb-2 align-items-center border-bottom pb-2 cuet-subject-edit-row';
                row.dataset.index = idx;

                // Paper Name — free text with datalist suggestions pulled from the
                // CUET master list. Picking a suggested name auto-fills Paper Code.
                const nameCol = document.createElement('div');
                nameCol.className = 'col-md-4';
                const nameLbl = document.createElement('label');
                nameLbl.className = 'form-label small mb-0';
                nameLbl.textContent = 'Subject Name (Paper)';
                const nameInput = document.createElement('input');
                nameInput.type = 'text';
                nameInput.setAttribute('list', datalistId);
                nameInput.className = 'form-control form-control-sm cuet-subject-field';
                nameInput.dataset.field = 'subjectName';
                nameInput.value = sub.subjectName ?? '';
                nameCol.append(nameLbl, nameInput);
                row.appendChild(nameCol);

                const codeCol = document.createElement('div');
                codeCol.className = 'col-md-2';
                const codeLbl = document.createElement('label');
                codeLbl.className = 'form-label small mb-0';
                codeLbl.textContent = 'Paper Code';
                const codeInput = document.createElement('input');
                codeInput.type = 'text';
                codeInput.className = 'form-control form-control-sm cuet-subject-field';
                codeInput.dataset.field = 'paperCode';
                codeInput.value = sub.paperCode ?? '';
                codeCol.append(codeLbl, codeInput);
                row.appendChild(codeCol);

                // Auto-fill the paper code (and canonicalize the name) whenever
                // the typed/selected subject name matches a known CUET paper.
                nameInput.addEventListener('input', () => {
                    const match = (allCuetPapersCache || []).find(p => p.paperName === nameInput.value);
                    if (match) codeInput.value = match.paperCode;
                });

                [
                    { field: 'score', label: 'Score', colClass: 'col-md-2', type: 'number' },
                    { field: 'percentile', label: 'Percentile', colClass: 'col-md-2', type: 'number' }
                ].forEach(({ field, label, colClass, type }) => {
                    const col = document.createElement('div');
                    col.className = colClass;
                    const lbl = document.createElement('label');
                    lbl.className = 'form-label small mb-0';
                    lbl.textContent = label;
                    const input = document.createElement('input');
                    input.type = type;
                    input.className = 'form-control form-control-sm cuet-subject-field';
                    input.dataset.field = field;
                    input.value = sub[field] ?? '';
                    col.append(lbl, input);
                    row.appendChild(col);
                });

                const delCol = document.createElement('div');
                delCol.className = 'col-md-2 d-flex align-items-end';
                const delBtn = document.createElement('button');
                delBtn.type = 'button';
                delBtn.className = 'btn btn-sm btn-outline-danger';
                delBtn.innerHTML = '<i class="bi bi-trash"></i>';
                delBtn.addEventListener('click', () => {
                    currentEntranceDto.cuetScore.subjectScores.splice(idx, 1);
                    renderSubjectRows(currentEntranceDto.cuetScore.subjectScores);
                });
                delCol.appendChild(delBtn);
                row.appendChild(delCol);

                subjectsWrapper.appendChild(row);
            });
        };

        renderSubjectRows(cuet.subjectScores);

        const addBtn = document.createElement('button');
        addBtn.type = 'button';
        addBtn.className = 'btn btn-sm btn-outline-secondary mt-2';
        addBtn.innerHTML = '<i class="bi bi-plus-circle me-1"></i>Add Subject';
        addBtn.addEventListener('click', () => {
            if (!currentEntranceDto.cuetScore.subjectScores) {
                currentEntranceDto.cuetScore.subjectScores = [];
            }
            currentEntranceDto.cuetScore.subjectScores.push({ paperCode: '', subjectName: '', score: null, percentile: null });
            renderSubjectRows(currentEntranceDto.cuetScore.subjectScores);
        });
        container.appendChild(addBtn);
    };

    document.getElementById('save-entrance-btn').addEventListener('click', async () => {
        if (!currentEditAllotmentId || !currentEntranceDto?.cuetScore) return;

        document.querySelectorAll('.cuet-top-field').forEach(input => {
            const f = input.dataset.field;
            currentEntranceDto.cuetScore[f] = f === 'yearOfExam'
                ? (input.value ? parseInt(input.value) : null)
                : input.value;
        });

        const subjectRows = document.querySelectorAll('.cuet-subject-edit-row');
        subjectRows.forEach((row, idx) => {
            const sub = currentEntranceDto.cuetScore.subjectScores[idx];
            if (!sub) return;
            row.querySelectorAll('.cuet-subject-field').forEach(input => {
                const f = input.dataset.field;
                sub[f] = (f === 'score' || f === 'percentile')
                    ? (input.value !== '' ? parseFloat(input.value) : null)
                    : input.value;
            });
        });

        try {
            await axios.post(`/api/institute/allotments/${currentEditAllotmentId}/entrance-details`,
                currentEntranceDto, { headers: csrfHeaders() });
            showAlert('Success', 'CUET entrance details updated successfully.');
        } catch (err) {
            const msg = err?.response?.data?.message || 'Failed to save entrance details.';
            showAlert('Error', msg);
        }
    });

    // --- 6b. Documents Tab: Load all docs as cards with Replace button ---
    const loadDocumentsTab = async (allotmentId) => {
        const loading   = document.getElementById('doc-tab-loading');
        const content   = document.getElementById('doc-tab-content');
        const errorEl   = document.getElementById('doc-tab-error');
        const panel     = document.getElementById('doc-replace-panel');
        const container = document.getElementById('doc-list-container');

        loading.classList.remove('d-none');
        content.classList.add('d-none');
        errorEl.classList.add('d-none');
        panel.classList.add('d-none');
        container.replaceChildren();

        try {
            const res = await axios.get(`/api/institute/allotments/${allotmentId}/document-review`);
            loading.classList.add('d-none');

            const parser = new DOMParser();
            const doc = parser.parseFromString(res.data, 'text/html');
            doc.querySelectorAll('script, iframe, object, embed').forEach(el => el.remove());
            doc.querySelectorAll('*').forEach(el => {
                [...el.attributes].forEach(attr => {
                    if (attr.name.startsWith('on')) el.removeAttribute(attr.name);
                });
            });

            const previews = doc.querySelectorAll('.clickable-preview');

            if (previews.length === 0) {
                container.innerHTML = '<p class="text-muted small col-12">No documents found for this applicant.</p>';
            } else {
                previews.forEach(el => {
                    const docId   = el.dataset.documentId;
                    const docType = el.dataset.documentType
                                 || el.querySelector('.doc-type-label')?.textContent?.trim()
                                 || 'UNKNOWN';
                    const isImg   = el.querySelector('img') !== null;
                    const label   = docType.replace(/_/g, ' ');

                    const col = document.createElement('div');
                    col.className = 'col-md-4 col-sm-6';
                    col.innerHTML = `
                        <div class="card h-100 shadow-sm border">
                            <div class="card-body p-2 text-center doc-preview-thumb"
                                 data-doc-id="${docId}" style="cursor:pointer;min-height:110px;">
                                ${
                                    isImg
                                    ? `<img src="/applicants/documents/${docId}"
                                            class="img-fluid rounded mb-1"
                                            style="max-height:90px;object-fit:cover;">`
                                    : `<i class="bi bi-file-earmark-pdf-fill text-danger fs-1 d-block mb-1"></i>`
                                }
                                <div class="small fw-bold text-truncate" title="${label}">${label}</div>
                            </div>
                            <div class="card-footer p-1 text-center bg-white">
                                <button class="btn btn-sm btn-outline-warning doc-replace-btn"
                                        data-doc-type="${docType}" data-doc-id="${docId}">
                                    <i class="bi bi-arrow-repeat me-1"></i>Replace
                                </button>
                            </div>
                        </div>`;
                    container.appendChild(col);
                });

                // Thumbnail click → open viewer
                container.querySelectorAll('.doc-preview-thumb').forEach(thumb => {
                    thumb.addEventListener('click', () => {
                        const dId   = thumb.dataset.docId;
                        const isImg = thumb.querySelector('img') !== null;
                        viewerTitle.textContent = 'Document Viewer';
                        if (isImg) {
                            fullResImage.src = `/applicants/documents/${dId}`;
                            imgContainer.classList.remove('d-none');
                            pdfFrame.classList.add('d-none');
                        } else {
                            pdfFrame.src = `/applicants/documents/${dId}`;
                            pdfFrame.classList.remove('d-none');
                            imgContainer.classList.add('d-none');
                        }
                        viewerModal.show();
                    });
                });

                // Replace button → reveal inline panel
                container.querySelectorAll('.doc-replace-btn').forEach(btn => {
                    btn.addEventListener('click', () => {
                        const type = btn.dataset.docType;
                        document.getElementById('doc-replace-type').value       = type;
                        document.getElementById('doc-replace-title').textContent = `Replace: ${type.replace(/_/g, ' ')}`;
                        document.getElementById('doc-replace-status').textContent = '';
                        document.getElementById('doc-replace-file').value        = '';
                        panel.classList.remove('d-none');
                        panel.scrollIntoView({ behavior: 'smooth' });
                    });
                });
            }

            content.classList.remove('d-none');

        } catch (err) {
            loading.classList.add('d-none');
            errorEl.textContent = err?.response?.data?.message || 'Failed to load documents.';
            errorEl.classList.remove('d-none');
        }
    };

    document.getElementById('doc-replace-cancel').addEventListener('click', () => {
        document.getElementById('doc-replace-panel').classList.add('d-none');
    });

    document.getElementById('doc-replace-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        if (!currentEditAllotmentId) return;

        const statusEl  = document.getElementById('doc-replace-status');
        const docType   = document.getElementById('doc-replace-type').value;
        const fileInput = document.getElementById('doc-replace-file');
        if (!fileInput.files[0]) return;

        const fd = new FormData();
        fd.append('documentType', docType);
        fd.append('documentFile', fileInput.files[0]);

        statusEl.className = 'mt-2 small text-muted';
        statusEl.textContent = 'Uploading…';

        try {
            const res = await axios.post(
                `/api/institute/allotments/${currentEditAllotmentId}/documents/upload`,
                fd,
                { headers: { ...csrfHeaders(), 'Content-Type': 'multipart/form-data' } }
            );
            statusEl.className = 'mt-2 small text-success';
            statusEl.textContent = res.data.message || 'Replaced successfully.';
            document.getElementById('doc-replace-panel').classList.add('d-none');
            // Refresh the document cards
            loadDocumentsTab(currentEditAllotmentId);
        } catch (err) {
            statusEl.className = 'mt-2 small text-danger';
            statusEl.textContent = err?.response?.data?.message || 'Upload failed.';
        }
    });

    // --- 6c. Verification & Edit History (offcanvas) ---
    const historyOffcanvas = new bootstrap.Offcanvas(document.getElementById('historyOffcanvas'));

    const ACTION_BADGE = {
        VERIFIED: { cls: 'bg-success', icon: 'bi-check-circle-fill', label: 'Verified' },
        REJECTED: { cls: 'bg-danger', icon: 'bi-x-circle-fill', label: 'Rejected' },
        DETAILS_EDITED: { cls: 'bg-warning text-dark', icon: 'bi-pencil-fill', label: 'Details Edited' }
    };

    const formatDiffValue = (val) => {
        if (val === null || val === undefined) return '—';
        if (typeof val === 'object') {
            try {
                const s = JSON.stringify(val);
                return s.length > 80 ? s.slice(0, 80) + '…' : s;
            } catch {
                return '(complex value)';
            }
        }
        return String(val);
    };

    const renderChangedFields = (json) => {
        if (!json) return null;
        let changes;
        try { changes = JSON.parse(json); } catch { return null; }
        const list = document.createElement('ul');
        list.className = 'list-unstyled small mb-0 mt-2';
        Object.entries(changes).forEach(([field, val]) => {
            const li = document.createElement('li');
            const oldText = formatDiffValue(val.old);
            const newText = formatDiffValue(val.new);
            const oldEl = document.createElement('s');
            oldEl.className = 'text-danger';
            oldEl.textContent = oldText;
            const newEl = document.createElement('span');
            newEl.className = 'text-success';
            newEl.textContent = newText;
            li.append(`${field}: `, oldEl, ' → ', newEl);
            list.appendChild(li);
        });
        return list;
    };

    const openHistoryModal = async (applicantId, name) => {
        document.getElementById('history-applicant-name').textContent = name;
        const timeline = document.getElementById('history-timeline');
        const loading = document.getElementById('history-loading');
        const empty = document.getElementById('history-empty');
        timeline.replaceChildren();
        empty.classList.add('d-none');
        loading.classList.remove('d-none');
        historyOffcanvas.show();

        try {
            const res = await axios.get(`/api/institute/allotments/history/${applicantId}`);
            loading.classList.add('d-none');
            const entries = res.data || [];
            if (entries.length === 0) {
                empty.classList.remove('d-none');
                return;
            }
            entries.forEach(entry => {
                const badge = ACTION_BADGE[entry.actionType] || { cls: 'bg-secondary', icon: 'bi-info-circle', label: entry.actionType };

                const card = document.createElement('div');
                card.className = 'border rounded p-3';

                const header = document.createElement('div');
                header.className = 'd-flex justify-content-between align-items-start';

                const left = document.createElement('div');
                const span = document.createElement('span');
                span.className = `badge ${badge.cls} mb-1`;
                span.innerHTML = `<i class="bi ${badge.icon} me-1"></i>${badge.label}`;
                const inst = document.createElement('div');
                inst.className = 'fw-bold small';
                inst.textContent = entry.instituteName;
                const round = document.createElement('div');
                round.className = 'text-muted x-small';
                round.textContent = `${entry.roundType || ''} ${entry.phaseNo ? 'Phase ' + entry.phaseNo : ''}${entry.programmeName ? ' · ' + entry.programmeName : ''}`;
                left.append(span, inst, round);

                const time = document.createElement('div');
                time.className = 'text-muted x-small text-end';
                time.textContent = new Date(entry.performedAt).toLocaleString();

                header.append(left, time);
                card.appendChild(header);

                if (entry.remarks) {
                    const remarks = document.createElement('div');
                    remarks.className = 'small mt-2 fst-italic';
                    remarks.textContent = `"${entry.remarks}"`;
                    card.appendChild(remarks);
                }

                const changedList = renderChangedFields(entry.changedFields);
                if (changedList) card.appendChild(changedList);

                timeline.appendChild(card);
            });
        } catch (err) {
            loading.classList.add('d-none');
            empty.textContent = 'Failed to load history.';
            empty.classList.remove('d-none');
        }
    };

    const viewDocuments = async (id, name) => {
        document.getElementById('review-applicant-name').textContent = name;
        const body = document.getElementById('documentReviewBody');
        body.replaceChildren();
        const wrapper = document.createElement("div");
        wrapper.className = "text-center p-5";
        const spinner = document.createElement("div");
        spinner.className = "spinner-border";
        wrapper.appendChild(spinner);
        body.appendChild(wrapper);
        docListModal.show();

        try {
            const res = await axios.get(`/api/institute/allotments/${id}/document-review`);
            body.replaceChildren();

            const parser = new DOMParser();
            const doc = parser.parseFromString(res.data, "text/html");
            doc.querySelectorAll("script, iframe, object, embed").forEach(el => el.remove());
            doc.querySelectorAll("*").forEach(el => {
                [...el.attributes].forEach(attr => {
                    if (attr.name.startsWith("on")) el.removeAttribute(attr.name);
                });
            });
            Array.from(doc.body.childNodes).forEach(node => body.appendChild(node));

            body.querySelectorAll('.clickable-preview').forEach(el => {
                el.addEventListener('click', () => {
                    const docId = el.dataset.documentId;
                    const isImg = el.querySelector('img') !== null;
                    viewerTitle.textContent = "Document Viewer";
                    if (isImg) {
                        fullResImage.src = `/applicants/documents/${docId}`;
                        imgContainer.classList.remove('d-none');
                        pdfFrame.classList.add('d-none');
                    } else {
                        pdfFrame.src = `/applicants/documents/${docId}`;
                        pdfFrame.classList.remove('d-none');
                        imgContainer.classList.add('d-none');
                    }
                    viewerModal.show();
                });
            });
        } catch (err) {
            body.replaceChildren();
            const div = document.createElement("div");
            div.className = "alert alert-danger";
            div.textContent = "Failed to load docs.";
            body.appendChild(div);
        }
    };

    document.getElementById('confirm-rejection-btn').addEventListener('click', () => {
        const remarks = document.getElementById('rejectionRemarks').value.trim();
        if (!remarks) return showAlert('Wait', 'Please provide a reason for rejection.');
        performAction(currentAllotmentId, { status: 'INSTITUTE_REJECTED', remarks: remarks });
    });

    document.getElementById('confirm-verify-btn').addEventListener('click', () => {
        performAction(currentAllotmentId, { status: 'PENDING', remarks: 'Verified by Institute' });
    });

    document.querySelectorAll('button[data-bs-toggle="tab"]').forEach(btn => {
        btn.addEventListener('shown.bs.tab', (e) => {
            const key = e.target.id.replace('tab-', '').replace('-btn', '').toUpperCase();
            loadData(key, 0);
        });
    });

    document.querySelectorAll('.stats-card').forEach(card => {
        card.addEventListener('click', () => {
            const btnId = card.dataset.tabId;
            document.getElementById(btnId).click();
        });
    });

    loadData('PENDING', 0);
    loadStats();
});
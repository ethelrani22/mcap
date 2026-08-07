/* static/js/controller/admissions/merit-list.js */
(function () {

  const getAdmissionWindowCode = () => document.getElementById('admissionWindowCode')?.value;
  const getProgrammeId = () => document.getElementById('programmeId')?.value;
  const getStreamId = () => document.getElementById('streamId')?.value;
  const getRoundType = () => document.getElementById('roundType')?.value;
  const getPhaseNo = () => document.getElementById('phaseNo')?.value;
  const getProgrammeLevel = () => (document.getElementById('programmeLevelHidden')?.value || 'UG').toUpperCase();

  const tbody = document.getElementById('meritTbody');
  const stateContainer = document.getElementById('stateContainer');
  const statusBadge = document.getElementById('statusBadge');

  let currentMeritId = null;
  let currentMeritEntries = [];
  // Cache of ALL entries across all pages (populated lazily when View Applied Rules is clicked)
  let allEntriesCache = null;

  const pageSize = 10;
  const PAGE_WINDOW = 10; // max page buttons visible at once

  const searchInput = document.getElementById('meritSearchInput');
  const searchBtn = document.getElementById('meritSearchBtn');
  const searchClearBtn = document.getElementById('meritSearchClearBtn');
  let isSearchActive = false;

  document.addEventListener('DOMContentLoaded', () => {
    loadPage();
    if (searchBtn) searchBtn.addEventListener('click', runSearch);
    if (searchInput) searchInput.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter') { ev.preventDefault(); runSearch(); }
    });
    if (searchClearBtn) searchClearBtn.addEventListener('click', clearSearch);
  });

  async function fetchAllEntries() {
    if (allEntriesCache) return allEntriesCache;
    if (!currentMeritId) return [];
    // Large page size to pull every entry for this merit list in one request.
    const res = await fetch(`/merit-list/data/${encodeURIComponent(currentMeritId)}?page=0&size=100000`);
    const pagedData = await safeJson(res);
    allEntriesCache = (res.ok && pagedData?.data) ? pagedData.data : [];
    return allEntriesCache;
  }

  async function runSearch() {
    const term = (searchInput?.value || '').trim().toLowerCase();
    if (!term) return clearSearch();
    if (!currentMeritId) return;

    isSearchActive = true;
    if (searchClearBtn) searchClearBtn.classList.remove('d-none');
    showState('loading', 'Searching...');

    try {
      const all = await fetchAllEntries();
      const matches = all.filter(e =>
        (e.applicantName || '').toLowerCase().includes(term) ||
        (e.applicationNo || '').toLowerCase().includes(term)
      );

      const paginationControls = document.getElementById('paginationControls');
      if (paginationControls) paginationControls.replaceChildren();

      if (!matches.length) return showState('empty', `No candidates found matching "${searchInput.value.trim()}".`);

      renderTable(matches);
      if (statusBadge) {
        statusBadge.className = 'badge bg-info text-dark';
        statusBadge.textContent = `${matches.length} match${matches.length !== 1 ? 'es' : ''} found`;
      }
    } catch (e) {
      console.error(e);
      showState('error', 'Search failed. Please try again.');
    }
  }

  function clearSearch() {
    isSearchActive = false;
    if (searchInput) searchInput.value = '';
    if (searchClearBtn) searchClearBtn.classList.add('d-none');
    if (currentMeritId) loadMeritListById(currentMeritId, 0);
  }

  function levelSegment() { return getProgrammeLevel() === 'PG' ? 'pg' : 'ug'; }

  async function loadPage() {
    currentMeritId = null;
    currentMeritEntries = [];
    allEntriesCache = null;

    if (tbody) tbody.replaceChildren();
    if (statusBadge) statusBadge.textContent = 'Refreshing...';

    const admissionWindowCode = getAdmissionWindowCode();
    const programmeId = getProgrammeId();

    if (!admissionWindowCode || !programmeId) return showState('error', 'Missing Parameters');

    showState('loading', 'Fetching merit list...');

    let url = `/merit-list/data/for-round-phase/${levelSegment()}?admissionWindowCode=${admissionWindowCode}&programmeId=${programmeId}&roundType=${getRoundType()}&phaseNo=${getPhaseNo()}`;
    if (levelSegment() === 'ug') url += `&streamId=${getStreamId()}`;

    try {
      const res = await fetch(url);
      const meta = await safeJson(res);

      if (!res.ok) return showState('error', 'Server Error');

      const status = String(meta?.status || '').toUpperCase();
      if (status === 'NO_ELIGIBLE') return showState('empty', 'No eligible candidates found.');
      if (status === 'NOT_GENERATED') return showState('empty', 'Merit list has not been generated yet.');
      if (status !== 'FOUND' || !meta?.meritListId) return showState('error', 'Merit list unavailable.');

      currentMeritId = meta.meritListId;
      await loadMeritListById(meta.meritListId, 0);

    } catch (e) {
      console.error(e);
      showState('error', 'Network Error');
    }
  }

  async function loadMeritListById(id, page = 0) {
    try {
      const res = await fetch(`/merit-list/data/${encodeURIComponent(id)}?page=${page}&size=${pageSize}`);
      const pagedData = await safeJson(res);

      if (!res.ok || !pagedData) return showState('error', 'Failed to load entries');

      currentMeritEntries = pagedData.data || [];
      if (currentMeritEntries.length === 0) return showState('empty', 'Merit list is empty');

      renderTable(currentMeritEntries);
      renderPagination(pagedData);

      if (statusBadge) {
        statusBadge.className = 'badge bg-success';
        statusBadge.textContent = `Published · Total ${pagedData.totalElements} Candidates`;
      }

    } catch (e) {
      console.error(e);
      showState('error', 'Data Parsing Error');
    }
  }

  window.loadPageData = function(pageIndex) {
    if (currentMeritId) loadMeritListById(currentMeritId, pageIndex);
  };

  // ── Windowed pagination: Prev | up to PAGE_WINDOW page buttons | Next ──
  function renderPagination(pagedData) {
    const container = document.getElementById('paginationControls');
    if (!container) return;
    container.replaceChildren();
    if (pagedData.totalPages <= 1) return;

    const currentPage = pagedData.page;       // 0-based
    const totalPages  = pagedData.totalPages;

    function createItem(label, pageIndex, disabled, active) {
      const li = document.createElement('li');
      li.className = `page-item${disabled ? ' disabled' : ''}${active ? ' active' : ''}`;
      const a = document.createElement('a');
      a.className = 'page-link';
      a.href = 'javascript:void(0)';
      a.textContent = label;
      if (!disabled) a.addEventListener('click', () => loadPageData(pageIndex));
      li.appendChild(a);
      return li;
    }

    function createEllipsis() {
      const li = document.createElement('li');
      li.className = 'page-item disabled';
      const span = document.createElement('span');
      span.className = 'page-link';
      span.textContent = '\u2026';
      li.appendChild(span);
      return li;
    }

    // Prev
    container.appendChild(createItem('Prev', currentPage - 1, currentPage === 0, false));

    // Compute window of PAGE_WINDOW pages centred on currentPage
    let winStart = Math.max(0, currentPage - Math.floor(PAGE_WINDOW / 2));
    let winEnd   = winStart + PAGE_WINDOW - 1;
    if (winEnd >= totalPages) {
      winEnd   = totalPages - 1;
      winStart = Math.max(0, winEnd - PAGE_WINDOW + 1);
    }

    // Leading ellipsis
    if (winStart > 0) {
      container.appendChild(createItem(1, 0, false, currentPage === 0));
      if (winStart > 1) container.appendChild(createEllipsis());
    }

    // Page window
    for (let i = winStart; i <= winEnd; i++) {
      container.appendChild(createItem(i + 1, i, false, currentPage === i));
    }

    // Trailing ellipsis
    if (winEnd < totalPages - 1) {
      if (winEnd < totalPages - 2) container.appendChild(createEllipsis());
      container.appendChild(createItem(totalPages, totalPages - 1, false, currentPage === totalPages - 1));
    }

    // Next
    container.appendChild(createItem('Next', currentPage + 1, pagedData.last, false));
  }

  window.downloadPdf = function() {
    if (!currentMeritId) return alert('Merit list ID not found.');
    window.location.href = `/merit-list/data/${encodeURIComponent(String(currentMeritId))}/export/pdf`;
  };

  // ── View Applied Rules: calls the dedicated /applied-rules endpoint ──
  window.showMeritRules = async function() {
    const modalBody = document.getElementById('rulesModalBody');
    if (!modalBody) return;
    modalBody.replaceChildren();

    if (!currentMeritId) return alert('No merit data loaded yet.');

    // Show spinner
    const spinner = document.createElement('div');
    spinner.className = 'text-center py-4';
    spinner.innerHTML = '<div class="spinner-border text-primary" role="status"><span class="visually-hidden">Loading...</span></div><p class="mt-2 mb-0 text-muted">Loading rules...</p>';
    modalBody.appendChild(spinner);
    new bootstrap.Modal(document.getElementById('rulesModal')).show();

    try {
      const admissionWindowCode = getAdmissionWindowCode();
      const programmeId = getProgrammeId();
      const roundType   = getRoundType();

      // Determine applicant type from round type
      const applicantType = (roundType || '').toUpperCase().includes('NON')
        ? 'WITHOUT_ENTRANCE'
        : 'WITH_ENTRANCE';

      const res = await fetch(
        `/merit-list/data/applied-rules?admissionWindowCode=${encodeURIComponent(admissionWindowCode)}&programmeId=${encodeURIComponent(programmeId)}&applicantType=${applicantType}`
      );
      const rules = await safeJson(res);

      modalBody.replaceChildren();

      if (!res.ok || !Array.isArray(rules) || !rules.length) {
        const div = document.createElement('div');
        div.className = 'text-center py-4 text-muted';
        div.innerHTML = '<i class="bi bi-info-circle fs-3 d-block mb-2"></i><p class="mb-0">No rule information is available for this merit list.</p>';
        modalBody.appendChild(div);
        return;
      }

      const info = document.createElement('p');
      info.className = 'text-muted mb-3 small';
      info.textContent = 'The following merit rules were applied to rank the candidates in this list:';
      modalBody.appendChild(info);

      const list = document.createElement('div');
      list.className = 'list-group shadow-sm';

      rules.forEach(rule => {
        const item = document.createElement('div');
        item.className = 'list-group-item py-3 d-flex gap-3 align-items-start';

        const badge = document.createElement('span');
        badge.className = 'badge bg-primary rounded-pill mt-1 flex-shrink-0';
        badge.textContent = rule.ruleNumber;

        const textDiv = document.createElement('div');
        const title = document.createElement('h6');
        title.className = 'fw-bold mb-1 text-dark';
        title.textContent = rule.ruleName || ('Rule ' + rule.ruleNumber);
        const descEl = document.createElement('p');
        descEl.className = 'small text-secondary mb-0';
        descEl.textContent = rule.ruleDescription || '—';
        const countEl = document.createElement('span');
        countEl.className = 'badge bg-light text-dark border mt-1';
        countEl.textContent = rule.applicantCount + ' candidate' + (rule.applicantCount !== 1 ? 's' : '');
        textDiv.append(title, descEl, countEl);
        item.append(badge, textDiv);
        list.appendChild(item);
      });

      modalBody.appendChild(list);

    } catch (e) {
      console.error(e);
      modalBody.replaceChildren();
      const err = document.createElement('div');
      err.className = 'alert alert-danger';
      err.textContent = 'Failed to load rules. Please try again.';
      modalBody.appendChild(err);
    }
  };

  function renderTable(entries) {
    if (stateContainer) stateContainer.classList.add('d-none');
    if (tbody) tbody.replaceChildren();

    entries.forEach(e => {
      const tr = document.createElement('tr');

      const tdRank = document.createElement('td');
      tdRank.className = 'text-center';
      tdRank.textContent = e.rank || '-';

      const tdApp = document.createElement('td');
      tdApp.textContent = e.applicationNo || '';

      const tdName = document.createElement('td');
      tdName.textContent = e.applicantName || '';

      const tdCat = document.createElement('td');
      tdCat.textContent = e.category || 'General';

      const tdRule = document.createElement('td');
      tdRule.className = 'text-center';
      // Integer.MAX_VALUE (2147483647) means unqualified — show a dash instead
      const ruleNum = e.qualifiedRuleNumber;
      const isUnqualified = ruleNum == null || ruleNum >= 2147483647;
      if (!isUnqualified) {
        const b = document.createElement('span');
        b.className = 'badge bg-secondary';
        b.textContent = 'Rule ' + ruleNum;
        tdRule.appendChild(b);
      } else if (!isUnqualified && e.selectionCriteria) {
        const b = document.createElement('span');
        b.className = 'badge bg-secondary';
        b.textContent = e.selectionCriteria;
        tdRule.appendChild(b);
      } else {
        const b = document.createElement('span');
        b.className = 'badge bg-light text-muted border';
        b.textContent = 'Unqualified';
        tdRule.appendChild(b);
      }

      const tdScore = document.createElement('td');
      tdScore.className = 'text-end';
      tdScore.textContent = e.meritScore != null ? Number(e.meritScore).toFixed(2) : '0.00';

      tr.append(tdRank, tdApp, tdName, tdCat, tdRule, tdScore);
      tbody.appendChild(tr);
    });
  }

  function showState(type, msg) {
    if (tbody) tbody.replaceChildren();
    if (stateContainer) {
      stateContainer.classList.remove('d-none');
      stateContainer.replaceChildren();
      const div = document.createElement('div');
      div.className = 'text-muted';
      const h = document.createElement('h5');
      h.textContent = msg;
      div.appendChild(h);
      stateContainer.appendChild(div);
    }
    const paginationControls = document.getElementById('paginationControls');
    if (paginationControls) paginationControls.replaceChildren();
  }

  async function safeJson(res) {
    try { return await res.json(); } catch { return null; }
  }

})();
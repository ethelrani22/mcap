/**
 * Reusable OTP confirmation modal.
 *
 * Wraps the existing /captcha/get-captcha, /otp/send-otp and /otp/verify-otp
 * endpoints in a single confirmWithOtp() call so any "are you sure / final
 * submit" action can require OTP confirmation before proceeding.
 *
 * CSP-compliant: modal markup is built with DOM APIs / template strings that
 * contain no inline event handlers or inline styles; all behaviour is wired
 * up via addEventListener in this file.
 */

const MODAL_ID = 'otp-confirmation-modal';
const RESEND_COOLDOWN_SECONDS = 30;

function buildModal() {
    const existing = document.getElementById(MODAL_ID);
    if (existing) existing.remove();

    const html = `
        <div class="modal fade" id="${MODAL_ID}" tabindex="-1" aria-labelledby="${MODAL_ID}-label" aria-hidden="true" data-bs-backdrop="static" data-bs-keyboard="false">
            <div class="modal-dialog modal-dialog-centered">
                <div class="modal-content border-0 shadow-lg">
                    <div class="modal-header bg-success text-white">
                        <h5 class="modal-title" id="${MODAL_ID}-label">Confirm OTP to Proceed</h5>
                        <button type="button" class="btn-close btn-close-white" data-bs-dismiss="modal" aria-label="Close"></button>
                    </div>
                    <div class="modal-body p-4">
                        <p class="text-muted small mb-3" id="${MODAL_ID}-intro">
                            To finalize your submission, please verify the OTP sent to your registered mobile number
                            <strong id="${MODAL_ID}-phone-display"></strong>.
                        </p>

                        <div id="${MODAL_ID}-error" class="alert alert-danger d-none" role="alert"></div>
                        <div id="${MODAL_ID}-success" class="alert alert-success d-none" role="alert"></div>

                        <!-- STEP 1: captcha + send OTP -->
                        <div id="${MODAL_ID}-step-send">
                            <div class="mb-3">
                                <label class="form-label fw-bold">Captcha</label>
                                <div class="d-flex align-items-center mb-2">
                                    <div class="border rounded bg-light px-3 py-2">
                                        <div id="${MODAL_ID}-captcha-image"></div>
                                    </div>
                                    <button type="button" id="${MODAL_ID}-refresh-captcha" class="btn btn-outline-secondary btn-sm ms-2" title="Refresh Captcha" aria-label="Refresh Captcha">
                                        <i class="bi bi-arrow-repeat"></i>
                                    </button>
                                </div>
                                <input type="text" class="form-control" id="${MODAL_ID}-captcha-input" maxlength="6" placeholder="Enter captcha" autocomplete="off">
                            </div>
                            <div class="text-center mt-4">
                                <button type="button" id="${MODAL_ID}-send-btn" class="btn btn-success px-5">Send OTP</button>
                            </div>
                        </div>

                        <!-- STEP 2: enter OTP + fresh captcha -->
                        <div id="${MODAL_ID}-step-verify" class="d-none">
                            <div class="mb-3">
                                <label for="${MODAL_ID}-otp-input" class="form-label fw-bold">Enter OTP</label>
                                <input type="tel" class="form-control" id="${MODAL_ID}-otp-input" maxlength="6"
                                       inputmode="numeric" pattern="[0-9]{6}" autocomplete="one-time-code"
                                       placeholder="Enter 6 digit OTP">
                                <div class="form-text">OTP valid for 5 minutes.</div>
                            </div>
                            <div class="mb-3">
                                <label class="form-label fw-bold">Captcha</label>
                                <div class="d-flex align-items-center mb-2">
                                    <div class="border rounded bg-light px-3 py-2">
                                        <div id="${MODAL_ID}-captcha-image-2"></div>
                                    </div>
                                    <button type="button" id="${MODAL_ID}-refresh-captcha-2" class="btn btn-outline-secondary btn-sm ms-2" title="Refresh Captcha" aria-label="Refresh Captcha">
                                        <i class="bi bi-arrow-repeat"></i>
                                    </button>
                                </div>
                                <input type="text" class="form-control" id="${MODAL_ID}-captcha-input-2" maxlength="6" placeholder="Enter captcha" autocomplete="off">
                            </div>
                            <div class="text-center mt-4">
                                <button type="button" id="${MODAL_ID}-verify-btn" class="btn btn-success px-5">Verify OTP</button>
                            </div>
                            <div class="text-center mt-3">
                                <button type="button" id="${MODAL_ID}-resend-btn" class="btn btn-link text-decoration-none" disabled>
                                    Resend OTP in ${RESEND_COOLDOWN_SECONDS}s
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>`;

    document.body.insertAdjacentHTML('beforeend', html);
    return document.getElementById(MODAL_ID);
}

function setError(msg) {
    const errEl = document.getElementById(`${MODAL_ID}-error`);
    const okEl = document.getElementById(`${MODAL_ID}-success`);
    okEl.classList.add('d-none');
    if (!msg) {
        errEl.classList.add('d-none');
        return;
    }
    errEl.textContent = msg;
    errEl.classList.remove('d-none');
}

function setSuccess(msg) {
    const errEl = document.getElementById(`${MODAL_ID}-error`);
    const okEl = document.getElementById(`${MODAL_ID}-success`);
    errEl.classList.add('d-none');
    if (!msg) {
        okEl.classList.add('d-none');
        return;
    }
    okEl.textContent = msg;
    okEl.classList.remove('d-none');
}

function loadCaptcha(containerId) {
    return axios.post('/captcha/get-captcha', null, {
        headers: { 'X-Requested-With': 'XMLHttpRequest' }
    }).then((response) => {
        const container = document.getElementById(containerId);
        if (!container) return;
        container.replaceChildren();
        const img = document.createElement('img');
        img.alt = 'CAPTCHA';
        img.src = 'data:image/png;base64,' + response.data;
        container.appendChild(img);
    }).catch(() => {
        setError('Could not load captcha. Please try again.');
    });
}

/**
 * Shows the OTP confirmation modal and resolves to true once the OTP has
 * been verified, or false if the user cancels.
 *
 * @param {Object} options
 * @param {string} options.countryCode   e.g. "+91"
 * @param {string} options.phoneNumber   applicant's registered mobile number
 * @param {string} options.maskedPhone   masked phone for display, e.g. "XXXXXX1234"
 * @param {string} options.purpose       OtpPurpose enum value, e.g. "PAYMENT_VERIFICATION"
 */
export function confirmWithOtp({ countryCode, phoneNumber, maskedPhone, purpose }) {
    return new Promise((resolve) => {
        if (!countryCode || !phoneNumber) {
            setError(null);
            resolve(false);
            return;
        }

        const modalEl = buildModal();
        const bsModal = new bootstrap.Modal(modalEl, { backdrop: 'static', keyboard: false });

        document.getElementById(`${MODAL_ID}-phone-display`).textContent =
             `${countryCode} ${phoneNumber}`.trim();

        const stepSend = document.getElementById(`${MODAL_ID}-step-send`);
        const stepVerify = document.getElementById(`${MODAL_ID}-step-verify`);
        const sendBtn = document.getElementById(`${MODAL_ID}-send-btn`);
        const verifyBtn = document.getElementById(`${MODAL_ID}-verify-btn`);
        const resendBtn = document.getElementById(`${MODAL_ID}-resend-btn`);

        let settled = false;
        let resendTimer = null;

        const finish = (result) => {
            if (settled) return;
            settled = true;
            if (resendTimer) clearInterval(resendTimer);
            bsModal.hide();
            resolve(result);
        };

        modalEl.addEventListener('hidden.bs.modal', () => {
            if (resendTimer) clearInterval(resendTimer);
            modalEl.remove();
            if (!settled) {
                settled = true;
                resolve(false);
            }
        });

        document.getElementById(`${MODAL_ID}-refresh-captcha`)
            .addEventListener('click', () => loadCaptcha(`${MODAL_ID}-captcha-image`));
        document.getElementById(`${MODAL_ID}-refresh-captcha-2`)
            .addEventListener('click', () => loadCaptcha(`${MODAL_ID}-captcha-image-2`));

        function startResendCooldown() {
            let remaining = RESEND_COOLDOWN_SECONDS;
            resendBtn.disabled = true;
            resendBtn.textContent = `Resend OTP in ${remaining}s`;
            resendTimer = setInterval(() => {
                remaining -= 1;
                if (remaining <= 0) {
                    clearInterval(resendTimer);
                    resendBtn.disabled = false;
                    resendBtn.textContent = 'Resend OTP';
                } else {
                    resendBtn.textContent = `Resend OTP in ${remaining}s`;
                }
            }, 1000);
        }

        function doSend(isResend) {
            const captchaInputId = isResend ? `${MODAL_ID}-captcha-input-2` : `${MODAL_ID}-captcha-input`;
            const captcha = document.getElementById(captchaInputId).value.trim();
            if (!captcha) {
                setError('Please enter the captcha.');
                return;
            }

            const btn = isResend ? resendBtn : sendBtn;
            const originalText = btn.textContent;
            btn.disabled = true;
            btn.textContent = 'Sending...';

            axios.post('/otp/send-otp', {
                countryCode,
                phoneNumber,
                purpose,
                captcha
            }).then(() => {
                setError(null);
                setSuccess('OTP sent successfully to your registered mobile number.');
                stepSend.classList.add('d-none');
                stepVerify.classList.remove('d-none');
                loadCaptcha(`${MODAL_ID}-captcha-image-2`);
                document.getElementById(`${MODAL_ID}-otp-input`).value = '';
                document.getElementById(`${MODAL_ID}-captcha-input-2`).value = '';
                startResendCooldown();
            }).catch((err) => {
                setSuccess(null);
                setError(err.response?.data?.message || 'Failed to send OTP. Please try again.');
                loadCaptcha(`${MODAL_ID}-captcha-image`);
                document.getElementById(`${MODAL_ID}-captcha-input`).value = '';
            }).finally(() => {
                btn.disabled = false;
                btn.textContent = isResend ? 'Resend OTP' : originalText;
            });
        }

        sendBtn.addEventListener('click', () => doSend(false));
        resendBtn.addEventListener('click', () => doSend(true));

        verifyBtn.addEventListener('click', () => {
            const otp = document.getElementById(`${MODAL_ID}-otp-input`).value.trim();
            const captcha = document.getElementById(`${MODAL_ID}-captcha-input-2`).value.trim();

            if (!/^\d{4,6}$/.test(otp)) {
                setError('Please enter a valid OTP.');
                return;
            }
            if (!captcha) {
                setError('Please enter the captcha.');
                return;
            }

            verifyBtn.disabled = true;
            verifyBtn.textContent = 'Verifying...';

            axios.post('/otp/verify-otp', {
                countryCode,
                phoneNumber,
                purpose,
                otp,
                captcha
            }).then(() => {
                setError(null);
                setSuccess('OTP verified successfully.');
                finish(true);
            }).catch((err) => {
                setSuccess(null);
                setError(err.response?.data?.message || 'Invalid or expired OTP. Please try again.');
                loadCaptcha(`${MODAL_ID}-captcha-image-2`);
                document.getElementById(`${MODAL_ID}-captcha-input-2`).value = '';
            }).finally(() => {
                verifyBtn.disabled = false;
                verifyBtn.textContent = 'Verify OTP';
            });
        });

        loadCaptcha(`${MODAL_ID}-captcha-image`);
        bsModal.show();
    });
}
document.addEventListener('DOMContentLoaded', function () {

    const form = document.getElementById('createUserForm');
    if (!form) return;

    // Department toggle
    const ownerType = document.getElementById('orgOwnerType');
    const departmentDiv = document.getElementById('departmentDiv');
    const department = document.getElementById('allottedDepartmentId');

    function toggleDepartment() {
        if (!ownerType || !departmentDiv || !department) return;

        if (ownerType.value === 'INSTITUTE_DEPARTMENT') {
            departmentDiv.classList.remove('d-none');
            department.required = true;
        } else {
            departmentDiv.classList.add('d-none');
            department.required = false;
            department.value = '';
        }
    }

    if (ownerType) {
        ownerType.addEventListener('change', toggleDepartment);
        toggleDepartment();
    }

    form.addEventListener('submit', async function (e) {
        e.preventDefault();

        // Remove old validation states
        form.querySelectorAll('.is-invalid').forEach(el => el.classList.remove('is-invalid'));

        // HTML5 validation
        if (!form.checkValidity()) {
            form.querySelectorAll(':invalid').forEach(el => el.classList.add('is-invalid'));
            return;
        }

        // Confirm password validation
        const passwordField = document.getElementById('password');
        const confirmField = document.getElementById('confirmPassword');

        if (passwordField && confirmField && passwordField.value !== confirmField.value) {
            confirmField.classList.add('is-invalid');
            confirmField.nextElementSibling.textContent = "Passwords do not match.";
            return;
        }

        // Convert form to JSON
        const data = formToJSON(form);

        // Remove confirm password before sending
        delete data.confirmPassword;

        try {

            const res = await axios.post('/user-management/data/create-user', data, {
                headers: {
                    'Content-Type': 'application/json'
                }
            });

            showAlert('success', extractMessage(res.data, 'User created successfully.'));
            form.reset();
            toggleDepartment();

        } catch (err) {

            const errors = err?.response?.data?.errors;

            if (Array.isArray(errors)) {

                errors.forEach(error => {

                    let msg = '';

                    if (typeof error === 'string') {
                        msg = error;
                    } else if (error && typeof error === 'object') {
                        msg = error.message || error.defaultMessage || JSON.stringify(error);
                    }

                    if (!msg) return;

                    const lower = msg.toLowerCase();

                    if (lower.includes('username')) {
                        markInvalid('username', msg);
                    }
                    else if (lower.includes('password')) {
                        markInvalid('password', msg);
                    }
                    else if (lower.includes('role')) {
                        markInvalid('roleName', msg);
                    }
                    else if (lower.includes('orgownertype')) {
                        markInvalid('orgOwnerType', msg);
                    }
                    else if (lower.includes('allotteddepartment') || lower.includes('department')) {
                        markInvalid('allottedDepartmentId', msg);
                    }
                    else {
                        showAlert('danger', msg);
                    }

                });

            } else {

                showAlert(
                    'danger',
                    extractMessage(
                        err?.response?.data ?? err?.message,
                        'Failed to create user.'
                    )
                );

            }

        }

    });

    function markInvalid(fieldId, message) {

        const input = document.getElementById(fieldId);

        if (!input) return;

        input.classList.add('is-invalid');

        let feedback = input.nextElementSibling;

        if (!feedback || !feedback.classList.contains('invalid-feedback')) {
            feedback = document.createElement('div');
            feedback.className = 'invalid-feedback';
            input.insertAdjacentElement('afterend', feedback);
        }

        feedback.textContent = message;
    }

    function extractMessage(data, fallback) {

        if (!data) return fallback;

        if (typeof data === 'string') return data;

        if (typeof data.message === 'string' && data.message.trim()) {
            return data.message;
        }

        if (Array.isArray(data.errors) && data.errors.length) {
            return data.errors
                .map(e => typeof e === 'string' ? e : e.message || '')
                .filter(Boolean)
                .join('<br>');
        }

        try {
            return JSON.stringify(data);
        } catch {
            return fallback;
        }
    }

    function showAlert(type, message) {

        const box = document.getElementById('alert-box');

        if (!box) return;

        box.innerHTML = `
            <div class="alert alert-${type} alert-dismissible fade show" role="alert">
                ${message}
                <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
            </div>
        `;
    }

});
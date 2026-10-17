// The login page. It sends the username and password to the Java server.
// If they are right, the server sets a cookie and we go to the website.

document.getElementById('loginForm').addEventListener('submit', async function (event) {
  event.preventDefault();
  const error = document.getElementById('error');
  const button = document.getElementById('loginBtn');
  error.classList.add('hidden');
  button.disabled = true;
  button.textContent = 'Logging in...';

  try {
    const response = await fetch('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      // sent in the body of the request, not in the address, so it is not saved in the browser history
      body: new URLSearchParams({
        username: document.getElementById('username').value,
        password: document.getElementById('password').value
      })
    });
    const result = await response.json();
    if (response.ok) {
      window.location = '/';
      return;
    }
    error.textContent = result.error || 'Could not log in.';
    error.classList.remove('hidden');
  } catch (e) {
    error.textContent = 'Cannot reach the server. Is the Java program running?';
    error.classList.remove('hidden');
  }
  document.getElementById('password').value = '';
  document.getElementById('password').focus();
  button.disabled = false;
  button.textContent = 'Log in';
});

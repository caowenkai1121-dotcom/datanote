/* DataNote 前端 API 客户端 (P2-01.1 自 workspace.html 抽出)。
   全局 window.api / window.apiPost / window._handleResponse, 与原内联行为完全一致。
   fetch 已由 dn-common.js 全局包装注入 CSRF token, 故须在 dn-common.js 之后加载。 */
function _handleResponse(r) {
  if (!r.ok) {
    if (r.status === 401) {
      // 登录态失效 → 先提示再跳(原直接跳突兀); 单次守卫: 并发多个401只跳一次, 不toast刷屏/多次跳
      if (location.pathname.indexOf('login') < 0 && !window._authExpiredHandled) {
        window._authExpiredHandled = true;
        if (window.showToast) showToast('会话已失效, 正在跳转登录…', 'warning');
        setTimeout(function() { location.replace('login.html'); }, 1500);
      }
      return Promise.reject(new Error('未登录'));
    }
    if (r.status === 403) {
      // 功能级鉴权拒绝: 弹提示不跳转(登录态仍有效)
      return r.json().catch(function() { return {}; }).then(function(j) {
        var m = (j && j.msg) || '无操作权限, 请联系管理员';
        if (window.showToast) showToast(m, 'error');
        return Promise.reject(new Error(m));
      });
    }
    return r.text().then(function(text) {
      return Promise.reject(new Error('HTTP ' + r.status + ': ' + text.substring(0, 200)));
    });
  }
  return r.json();
}
function api(url, options) {
  return fetch(url, options).then(_handleResponse);
}
function apiPost(url, body) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }).then(_handleResponse);
}
function apiPut(url, body) {
  return fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }).then(_handleResponse);
}
function apiDel(url) {
  return fetch(url, { method: 'DELETE' }).then(_handleResponse);
}

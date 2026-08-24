/*
 * admin-tw-keywords.js — Steam-game tester on the TW keyword-management page.
 *
 * Sends the app ID (and whatever keyword is currently being typed in the
 * add-keyword form, if any) to /admin/tw/{id}/keywords/test and renders
 * which saved keywords / mapped Steam content-descriptor IDs match that
 * game's store page, plus whether the in-progress draft keyword would match.
 */
(function () {
  "use strict";

  const root = document.querySelector("[data-tw-kw-tester]");
  if (!root) return;

  const testUrl = root.dataset.testUrl;
  const csrfHeader = root.dataset.csrfHeader;
  const csrfToken = root.dataset.csrfToken;
  const appIdInput = root.querySelector("[data-tw-kw-appid-input]");
  const testButton = root.querySelector("[data-tw-kw-test-button]");
  const resultEl = root.querySelector("[data-tw-kw-test-result]");
  const draftInput = document.querySelector("[data-tw-kw-draft-input]");

  function el(tag, className, text) {
    const n = document.createElement(tag);
    if (className) n.className = className;
    if (text != null) n.textContent = String(text);
    return n;
  }

  function renderList(labelKey, items) {
    const wrap = el("div");
    wrap.style.marginBottom = "0.75rem";
    wrap.appendChild(el("strong", null, root.dataset[labelKey]));
    if (!items || items.length === 0) {
      wrap.appendChild(el("span", "text-muted", " " + root.dataset.i18nNone));
      return wrap;
    }
    const ul = el("ul");
    items.forEach(function (item) {
      ul.appendChild(el("li", null, item));
    });
    wrap.appendChild(ul);
    return wrap;
  }

  function renderResult(data) {
    resultEl.replaceChildren();
    if (!data || data.error) {
      resultEl.appendChild(el("p", "text-danger", root.dataset.i18nError));
      return;
    }

    resultEl.appendChild(renderList("i18nMatchedKeywords", data.matchedKeywords));
    resultEl.appendChild(renderList("i18nMatchedContentIds", data.matchedContentDescriptorIds));

    if (data.draftKeywordMatch != null) {
      const p = el("p", data.draftKeywordMatch ? "text-success" : "text-muted",
        data.draftKeywordMatch ? root.dataset.i18nDraftMatch : root.dataset.i18nDraftNoMatch);
      resultEl.appendChild(p);
    }

    const notesWrap = el("div");
    notesWrap.appendChild(el("strong", null, root.dataset.i18nNotesLabel));
    const pre = el("pre");
    pre.style.whiteSpace = "pre-wrap";
    pre.textContent = data.notes && data.notes.trim() !== "" ? data.notes : root.dataset.i18nNone;
    notesWrap.appendChild(pre);
    resultEl.appendChild(notesWrap);
  }

  function runTest() {
    const appId = appIdInput.value.trim();
    if (!appId) return;
    const draftKeyword = draftInput ? draftInput.value.trim() : "";
    const body = new URLSearchParams({ appId: appId, draftKeyword: draftKeyword });
    testButton.disabled = true;
    fetch(testUrl, {
      method: "POST",
      headers: { [csrfHeader]: csrfToken, "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString()
    })
      .then(function (r) { return r.json(); })
      .then(renderResult)
      .catch(function () { renderResult({ error: true }); })
      .finally(function () { testButton.disabled = false; });
  }

  testButton.addEventListener("click", runTest);
  appIdInput.addEventListener("keydown", function (e) {
    if (e.key === "Enter") { e.preventDefault(); runTest(); }
  });
}());

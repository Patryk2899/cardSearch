const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// A small DOM adapter exercises the UI's request/navigation behavior without dependencies.
class Element {
  constructor() {
    this.value = '';
    this.children = [];
    this.events = {};
    this.attributes = {};
    this.disabled = false;
    this.hidden = false;
    this.classList = { add() {}, remove() {}, toggle() {} };
  }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; }
  setAttribute(name, value) { this.attributes[name] = value; }
  getAttribute(name) { return this.attributes[name]; }
  removeAttribute(name) { delete this[name]; }
  addEventListener(name, callback) { this.events[name] = callback; }
  focus() {}
  scrollIntoView() {}
}

async function setup(entries = [
  { entryIndex: 0, cardName: 'Mountain', quantity: '2', options: [
    { id: 'a:-1', imageUrl: 'https://example.com/a.jpg', name: 'Mountain', setCode: 'aaa', setName: 'First set' },
    { id: 'b:-1', imageUrl: 'https://example.com/b.jpg', name: 'Mountain', setCode: 'fra', collectorNumber: '393' },
  ] },
]) {
  const source = fs.readFileSync(path.join(__dirname, '../app.js'), 'utf8');
  const html = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
  const nodes = Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)].map(match => ['#' + match[1], new Element()]));
  for (const match of source.matchAll(/querySelector\('#([^']+)'\)/g)) {
    assert.ok(nodes['#' + match[1]], `Missing UI element: ${match[1]}`);
  }
  nodes['#card-list-form'].reset = () => { nodes['#list-name'].value = ''; nodes['#list-text'].value = ''; };
  nodes['#save-toast'].hidden = true;
  const state = { listId: 'list', name: 'My list', cardsText: '2 Mountain', entries, selections: [] };
  const requests = [];
  const pdfRequests = [];
  let failSave = false;
  const fetch = async (url, options = {}) => {
    if (url.endsWith('/pdf-exports')) {
      pdfRequests.push({ url, ...options });
      return { ok: true, json: async () => ({ id: 'export-1', status: 'READY', message: '1 PDF ready to download.' }) };
    }
    if (url.endsWith('/image-options')) return { ok: true, json: async () => structuredClone(state) };
    if (url.endsWith('/image-selections')) {
      requests.push({ url, ...options });
      if (failSave) throw new Error('offline');
      state.selections = JSON.parse(options.body).selections;
      return { ok: true, json: async () => ({ savedCopies: String(state.selections.length) }) };
    }
    return { ok: true, json: async () => [{ id: 'list', name: 'My list', cardCount: 2, assignedImageCount: state.selections.length, lineCount: 1, createdAt: new Date().toISOString() }] };
  };
  vm.runInNewContext(source, {
    document: { querySelector: selector => nodes[selector], createElement: () => new Element() },
    fetch, window: { confirm: () => true },
    setTimeout: callback => { nodes.toastDismiss = callback; return 1; }, clearTimeout() {},
  });
  await new Promise(resolve => setImmediate(resolve));
  const imageButton = nodes['#saved-lists'].children[0].children[2].children[1];
  imageButton.events.click();
  await new Promise(resolve => setImmediate(resolve));
  return { nodes, state, requests, pdfRequests, imageButton, failSave: () => { failSave = true; } };
}

function choose(nodes, option) {
  nodes['#image-options'].children.find(choice => choice.value === option).events.click();
}

function selected(nodes) {
  return nodes['#image-options'].children.find(choice => choice.getAttribute('aria-pressed') === 'true')?.value;
}

test('each copy has its own image and saving sends every choice together', async () => {
  const { nodes, requests, imageButton } = await setup();
  assert.equal(nodes['#save-images'].disabled, true);
  assert.match(nodes['#saved-lists'].children[0].children[0].children[1].textContent, /Images: 0 \/ 2.*Not started/);
  assert.match(nodes['#image-copy-label'].textContent, /Copy 1 of 2/);
  assert.equal(nodes['#next-image-card'].hidden, false);
  assert.equal(nodes['#previous-image-card'].hidden, true);
  choose(nodes, 'a:-1');
  assert.equal(nodes['#image-options'].children.length, 2);
  assert.equal(nodes['#image-options'].children[0].children[0].src, 'https://example.com/a.jpg');
  assert.equal(nodes['#image-options'].children[1].children[0].src, 'https://example.com/b.jpg');
  assert.equal(selected(nodes), 'a:-1');
  assert.equal(nodes['#save-images'].disabled, false);
  nodes['#next-image-card'].events.click();
  assert.match(nodes['#image-copy-label'].textContent, /Copy 2 of 2/);
  assert.equal(nodes['#next-image-card'].hidden, true);
  assert.equal(nodes['#previous-image-card'].hidden, false);
  assert.equal(selected(nodes), undefined);
  choose(nodes, 'b:-1');
  assert.equal(nodes['#save-images'].disabled, false);
  assert.equal(requests.length, 0);
  nodes['#previous-image-card'].events.click();
  assert.equal(selected(nodes), 'a:-1');
  await nodes['#save-images'].events.click();
  assert.equal(requests.length, 1);
  assert.match(nodes['#saved-lists'].children[0].children[0].children[1].textContent, /Images: 2 \/ 2.*Complete/);
  assert.equal(requests[0].method, 'PUT');
  assert.deepEqual(JSON.parse(requests[0].body), { cardsText: '2 Mountain', selections: [
    { entryIndex: 0, copyIndex: '0', optionId: 'a:-1' },
    { entryIndex: 0, copyIndex: '1', optionId: 'b:-1' },
  ] });
  nodes['#close-image-picker'].events.click();
  imageButton.events.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(selected(nodes), 'a:-1');
  nodes['#next-image-card'].events.click();
  assert.equal(selected(nodes), 'b:-1');
});

test('failed saves retain all choices and allow retry', async () => {
  const { nodes, failSave } = await setup();
  choose(nodes, 'a:-1');
  nodes['#next-image-card'].events.click();
  choose(nodes, 'b:-1');
  failSave();
  await nodes['#save-images'].events.click();
  assert.match(nodes['#image-save-status'].textContent, /Cannot reach/);
  assert.equal(nodes['#save-toast'].hidden, true);
  assert.equal(selected(nodes), 'b:-1');
  assert.equal(nodes['#image-controls'].disabled, false);
  assert.equal(nodes['#save-images'].disabled, false);
});

test('partial image choices save to the list and restore when reopened', async () => {
  const { nodes, requests, imageButton } = await setup();
  choose(nodes, 'a:-1');
  await nodes['#save-images'].events.click();
  assert.equal(JSON.parse(requests[0].body).selections.length, 1);
  assert.match(nodes['#image-save-status'].textContent, /Saved images for 1 of 2/);
  assert.equal(nodes['#save-toast'].hidden, false);
  assert.match(nodes['#saved-lists'].children[0].children[0].children[1].textContent, /Images: 1 \/ 2.*In progress/);
  assert.match(nodes['#save-toast'].textContent, /Saved images for 1 of 2/);
  nodes.toastDismiss();
  assert.equal(nodes['#save-toast'].hidden, true);
  nodes['#close-image-picker'].events.click();
  imageButton.events.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(selected(nodes), 'a:-1');
  nodes['#next-image-card'].events.click();
  assert.equal(selected(nodes), undefined);
  assert.equal(nodes['#save-images'].disabled, false);
});

test('cards with no matching images block saving and explain how to resolve them', async () => {
  const { nodes, requests } = await setup([{ entryIndex: 0, cardName: 'Unknown', quantity: '1', options: [] }]);
  assert.equal(nodes['#image-options'].children.length, 0);
  assert.equal(nodes['#save-images'].disabled, true);
  assert.equal(nodes['#next-image-card'].hidden, true);
  assert.match(nodes['#image-match-status'].textContent, /No images found/);
  await nodes['#save-images'].events.click();
  assert.equal(requests.length, 0);
});

test('large quantities are navigated without expanding all copies into the DOM', async () => {
  const { nodes } = await setup([{ entryIndex: 0, cardName: 'Mountain', quantity: '999999999999999999999', options: [] }]);
  assert.match(nodes['#image-copy-label'].textContent, /Copy 1 of 999999999999999999999/);
  nodes['#next-image-card'].events.click();
  assert.match(nodes['#image-copy-label'].textContent, /Copy 2 of 999999999999999999999/);
  assert.equal(nodes['#image-options'].children.length, 0);
});

test('clicking another image changes selection without replacing the gallery', async () => {
  const { nodes } = await setup();
  const first = nodes['#image-options'].children[0];
  choose(nodes, 'a:-1');
  choose(nodes, 'b:-1');
  assert.equal(nodes['#image-options'].children[0], first);
  assert.equal(first.getAttribute('aria-pressed'), 'false');
  assert.equal(selected(nodes), 'b:-1');
  first.children[0].events.error();
  assert.equal(first.children[0].hidden, true);
  assert.equal(first.children[1].hidden, false);
});

test('PDF generation requires saved assignments and exposes the generated ZIP', async () => {
  const { nodes, pdfRequests } = await setup();
  const actions = () => nodes['#saved-lists'].children[0].children[2];
  assert.equal(actions().children[3].disabled, true);
  choose(nodes, 'a:-1');
  await nodes['#save-images'].events.click();
  assert.equal(actions().children[3].disabled, true);
  nodes['#next-image-card'].events.click();
  choose(nodes, 'b:-1');
  await nodes['#save-images'].events.click();
  assert.equal(actions().children[3].disabled, false);
  actions().children[3].events.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(pdfRequests.length, 1);
  assert.equal(pdfRequests[0].method, 'POST');
  assert.equal(actions().children[4].hidden, false);
  assert.equal(actions().children[4].href, '/api/card-lists/list/pdf-exports/export-1/download');
});

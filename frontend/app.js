function element(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}


const listForm = document.querySelector('#card-list-form');
const listName = document.querySelector('#list-name');
const listText = document.querySelector('#list-text');
const saveButton = document.querySelector('#save-list');
const saveStatus = document.querySelector('#save-status');
const listsStatus = document.querySelector('#lists-status');
const refreshButton = document.querySelector('#refresh-lists');
const newListButton = document.querySelector('#new-list');
const cancelEditButton = document.querySelector('#cancel-edit');
const formHeading = document.querySelector('#list-form-heading');
let editingListId = null;
let listLoadVersion = 0;

function setListFormBusy(busy) {
  saveButton.disabled = listName.disabled = listText.disabled = newListButton.disabled = cancelEditButton.disabled = busy;
}

function resetListEditor() {
  editingListId = null;
  listForm.reset();
  formHeading.textContent = 'Add a list';
  saveButton.textContent = 'Add list';
  cancelEditButton.hidden = true;
}

async function listRequest(path = '', options = {}) {
  let response;
  try {
    response = await fetch(`/api/card-lists${path}`, options);
  } catch {
    throw new Error('Cannot reach the server. Check your connection and try again.');
  }
  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.detail || 'Could not access saved lists. Please try again.');
  if (data === null) throw new Error('The server returned an unexpected response. Please try again.');
  return data;
}

function savedListElement(list) {
  const details = element('details', 'saved-list');
  const summary = element('summary', '', list.name);
  const date = new Date(list.createdAt).toLocaleString();
  summary.append(element('span', 'saved-list-meta', `${list.cardCount} ${list.cardCount === 1 ? 'card' : 'cards'} · ${list.lineCount} ${list.lineCount === 1 ? 'entry' : 'entries'} · ${date}`));
  const text = element('pre', '', 'Loading list…');
  const editButton = element('button', 'secondary-button', 'Edit list');
  editButton.type = 'button';
  const removeButton = element('button', 'danger-button', 'Remove list');
  removeButton.type = 'button';
  const removeStatus = element('p', 'remove-status');
  removeStatus.setAttribute('role', 'status');
  const actions = element('div', 'list-actions');
  actions.append(editButton, removeButton);
  details.append(summary, text, actions, removeStatus);
  editButton.addEventListener('click', async () => {
    if (saveButton.disabled) return;
    if ((listName.value || listText.value) && !window.confirm('Discard this draft and edit the saved list?')) return;
    setListFormBusy(true);
    saveStatus.textContent = 'Loading list for editing…';
    saveStatus.classList.remove('error');
    try {
      const saved = await listRequest(`/${encodeURIComponent(list.id)}`);
      editingListId = saved.id;
      listName.value = saved.name;
      listText.value = saved.cardsText;
      formHeading.textContent = 'Edit list';
      saveButton.textContent = 'Save changes';
      cancelEditButton.hidden = false;
      saveStatus.textContent = '';
    } catch (error) {
      saveStatus.textContent = error.message;
      saveStatus.classList.add('error');
    } finally {
      setListFormBusy(false);
      listName.focus();
    }
  });
  removeButton.addEventListener('click', async () => {
    if (saveButton.disabled) return;
    if (!window.confirm(`Remove “${list.name}”? This deletes the saved list.`)) return;
    removeButton.disabled = true;
    removeStatus.textContent = 'Removing…';
    removeStatus.classList.remove('error');
    try {
      await listRequest(`/${encodeURIComponent(list.id)}`, { method: 'DELETE' });
      if (editingListId === list.id) {
        resetListEditor();
        saveStatus.textContent = 'List removed.';
      }
      details.remove();
      await loadSavedLists();
      refreshButton.focus();
    } catch (error) {
      removeStatus.textContent = error.message;
      removeStatus.classList.add('error');
      removeButton.disabled = false;
    }
  });
  let loaded = false;
  let loading = false;
  details.addEventListener('toggle', async () => {
    if (!details.open || loaded || loading) return;
    loading = true;
    text.textContent = 'Loading list…';
    text.classList.remove('error');
    try {
      const saved = await listRequest(`/${encodeURIComponent(list.id)}`);
      text.textContent = saved.cardsText;
      loaded = true;
    } catch (error) {
      text.textContent = `${error.message} Close and reopen this list to retry.`;
      text.classList.add('error');
    } finally {
      loading = false;
    }
  });
  return details;
}

async function loadSavedLists() {
  const version = ++listLoadVersion;
  refreshButton.disabled = true;
  listsStatus.textContent = 'Loading saved lists…';
  listsStatus.classList.remove('error');
  try {
    const lists = await listRequest();
    if (version !== listLoadVersion) return;
    document.querySelector('#saved-lists').replaceChildren(...lists.map(savedListElement));
    listsStatus.textContent = lists.length ? '' : 'No saved lists yet. Paste your first list above.';
  } catch (error) {
    if (version !== listLoadVersion) return;
    listsStatus.textContent = error.message;
    listsStatus.classList.add('error');
  } finally {
    if (version === listLoadVersion) refreshButton.disabled = false;
  }
}

listForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (saveButton.disabled) return;
  saveStatus.classList.remove('error');
  if (!listText.value.trim()) {
    saveStatus.textContent = 'Paste at least one card before saving.';
    saveStatus.classList.add('error');
    listText.focus();
    return;
  }
  const request = { name: listName.value, cardsText: listText.value };
  const listId = editingListId;
  setListFormBusy(true);
  saveButton.textContent = 'Saving…';
  saveStatus.textContent = '';
  try {
    const saved = await listRequest(listId ? `/${encodeURIComponent(listId)}` : '', {
      method: listId ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    resetListEditor();
    saveStatus.textContent = `Saved “${saved.name}” (${saved.cardCount} ${saved.cardCount === 1 ? 'card' : 'cards'}).`;
    await loadSavedLists();
  } catch (error) {
    saveStatus.textContent = error.message;
    saveStatus.classList.add('error');
  } finally {
    setListFormBusy(false);
    saveButton.textContent = editingListId ? 'Save changes' : 'Add list';
  }
});
refreshButton.addEventListener('click', loadSavedLists);
newListButton.addEventListener('click', () => {
  if ((listName.value || listText.value) && !window.confirm('Discard this draft and start a new list?')) return;
  resetListEditor();
  saveStatus.textContent = '';
  saveStatus.classList.remove('error');
  listName.focus();
});
cancelEditButton.addEventListener('click', () => {
  if (!window.confirm('Discard changes to this list?')) return;
  resetListEditor();
  saveStatus.textContent = 'Editing canceled.';
  saveStatus.classList.remove('error');
  listName.focus();
});
loadSavedLists();

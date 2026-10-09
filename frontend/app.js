function element(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}


const saveToast = document.querySelector('#save-toast');
let saveToastTimeout;
function showSaveToast(message) {
  clearTimeout(saveToastTimeout);
  saveToast.textContent = message;
  saveToast.hidden = false;
  saveToastTimeout = setTimeout(() => { saveToast.hidden = true; }, 5000);
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
const pdfExports = new Map();
const pdfExportViews = new Map();

function updatePdfExportView(listId) {
  const view = pdfExportViews.get(listId);
  if (!view) return;
  const job = pdfExports.get(listId);
  view.button.disabled = !view.complete || !!job?.busy;
  view.button.textContent = job?.busy ? 'Generating PDFs…' : 'Generate PDFs';
  view.status.textContent = job?.message || '';
  view.status.classList.toggle('error', !!job?.failed);
  view.download.hidden = !job?.downloadUrl;
  if (job?.downloadUrl) view.download.href = job.downloadUrl;
}

async function generateListPdfs(listId) {
  if (pdfExports.get(listId)?.busy) return;
  const job = { busy: true, message: 'Starting PDF generation…' };
  pdfExports.set(listId, job);
  updatePdfExportView(listId);
  try {
    const path = `/${encodeURIComponent(listId)}/pdf-exports`;
    let result = await listRequest(path, { method: 'POST' });
    while (true) {
      job.message = result.message;
      updatePdfExportView(listId);
      if (result.status === 'FAILED') throw new Error(result.message);
      if (result.status === 'READY') {
        job.downloadUrl = `/api/card-lists${path}/${encodeURIComponent(result.id)}/download`;
        showSaveToast('PDFs are ready to download.');
        break;
      }
      await new Promise(resolve => setTimeout(resolve, 1500));
      result = await listRequest(`${path}/${encodeURIComponent(result.id)}`);
    }
  } catch (error) {
    job.failed = true;
    job.message = error.message;
  } finally {
    job.busy = false;
    updatePdfExportView(listId);
  }
}

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
  const assigned = BigInt(list.assignedImageCount || 0);
  const total = BigInt(list.cardCount);
  const complete = total > 0n && assigned === total;
  const imageStatus = complete ? 'Complete' : assigned > 0n ? 'In progress' : 'Not started';
  const imageBadge = element('span', `list-image-status ${complete ? 'complete' : assigned > 0n ? 'in-progress' : 'not-started'}`,
    `Images: ${assigned} / ${total} · ${imageStatus}`);
  const date = new Date(list.createdAt).toLocaleString();
  summary.append(element('span', 'saved-list-meta', `${list.cardCount} ${list.cardCount === 1 ? 'card' : 'cards'} · ${list.lineCount} ${list.lineCount === 1 ? 'entry' : 'entries'} · ${date}`));
  const text = element('pre', '', 'Loading list…');
  summary.append(imageBadge);
  const editButton = element('button', 'secondary-button', 'Edit list');
  editButton.type = 'button';
  const removeButton = element('button', 'danger-button', 'Remove list');
  const imageButton = element('button', 'secondary-button', 'Choose images');
  imageButton.type = 'button';
  imageButton.addEventListener('click', () => openImagePicker(list.id));
  removeButton.type = 'button';
  const removeStatus = element('p', 'remove-status');
  removeStatus.setAttribute('role', 'status');
  const actions = element('div', 'list-actions');
  const pdfButton = element('button', 'secondary-button', 'Generate PDFs');
  pdfButton.type = 'button';
  pdfButton.title = complete ? 'Generate nine cards per PDF using your Scribus template' : 'Assign and save an image for every card copy first';
  pdfButton.addEventListener('click', () => { if (!pdfButton.disabled) generateListPdfs(list.id); });
  const pdfStatus = element('p', 'pdf-export-status');
  pdfStatus.setAttribute('role', 'status');
  pdfStatus.setAttribute('aria-live', 'polite');
  const pdfDownload = element('a', 'pdf-download', 'Download PDFs (ZIP)');
  pdfDownload.hidden = true;
  pdfExportViews.set(list.id, { button: pdfButton, status: pdfStatus, download: pdfDownload, complete });
  updatePdfExportView(list.id);
  actions.append(editButton, imageButton, removeButton);
  actions.append(pdfButton, pdfDownload);
  details.append(summary, text, actions, removeStatus);
  details.append(pdfStatus);
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
      if (imageState?.listId === list.id) resetImagePicker();
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
    if (imageState?.listId === listId) resetImagePicker();
    resetListEditor();
    saveStatus.textContent = `Saved “${saved.name}” (${saved.cardCount} ${saved.cardCount === 1 ? 'card' : 'cards'}).`;
    showSaveToast('List saved successfully.');
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
const imagePicker = document.querySelector('#image-picker');
const imageControls = document.querySelector('#image-controls');
const imageOptions = document.querySelector('#image-options');
const imageSaveStatus = document.querySelector('#image-save-status');
const imageSaveButton = document.querySelector('#save-images');
const imageCloseButton = document.querySelector('#close-image-picker');
const previousImageButton = document.querySelector('#previous-image-card');
const nextImageButton = document.querySelector('#next-image-card');
let imageState = null;
let imageSelections = new Map();
let imageEntryIndex = 0;
let imageCopyIndex = 0n;
let imageDirty = false;
let imageLoading = false;

function resetImagePicker() {
  imageState = null;
  imageSelections.clear();
  imageDirty = false;
  imagePicker.hidden = true;
  imageOptions.replaceChildren();
  imageSaveButton.disabled = true;
}

async function openImagePicker(listId) {
  if (imageLoading || imageControls.disabled) return;
  if (imageDirty && !window.confirm('Discard unsaved image choices and open this list?')) return;
  imageLoading = true;
  imageSaveButton.disabled = true;
  imagePicker.hidden = false;
  imageControls.hidden = true;
  imageSaveStatus.textContent = '';
  document.querySelector('#image-progress').textContent = 'Loading available card images…';
  imagePicker.scrollIntoView({ behavior: 'smooth', block: 'start' });
  try {
    const state = await listRequest(`/${encodeURIComponent(listId)}/image-options`);
    imageState = state;
    imageSelections = new Map(state.selections.filter(selected =>
      state.entries[selected.entryIndex]?.options.some(option => option.id === selected.optionId)
    ).map(selected => [`${selected.entryIndex}:${selected.copyIndex}`, selected]));
    imageEntryIndex = state.entries.findIndex(entry => BigInt(entry.quantity) > 0n);
    imageCopyIndex = 0n;
    imageDirty = false;
    document.querySelector('#image-heading').textContent = `Choose images — ${state.name}`;
    imageControls.hidden = false;
    renderImageCopy();
  } catch (error) {
    resetImagePicker();
    imagePicker.hidden = false;
    document.querySelector('#image-progress').textContent = error.message;
  } finally {
    imageLoading = false;
  }
}

function imageTotal() {
  return imageState.entries.reduce((total, entry) => total + BigInt(entry.quantity), 0n);
}

function renderImageCopy() {
  const total = imageTotal();
  const entry = imageState.entries[imageEntryIndex];
  const position = imageEntryIndex < 0 ? 0n : imageState.entries.slice(0, imageEntryIndex)
    .reduce((count, item) => count + BigInt(item.quantity), 0n) + imageCopyIndex + 1n;
  document.querySelector('#image-progress').textContent = `Card ${position} of ${total} · ${imageSelections.size} of ${total} images selected`;
  document.querySelector('#image-card-name').textContent = entry?.cardName || 'No card copies in this list';
  document.querySelector('#image-copy-label').textContent = entry ? `Copy ${imageCopyIndex + 1n} of ${entry.quantity}` : '';
  const options = (entry?.options || []).map(option => {
    const label = `${option.name} · ${option.setName || option.setCode || 'Unknown set'} (${option.setCode || '?'}) ${option.collectorNumber || ''} · ${option.lang || ''}`;
    const choice = element('button', 'image-option');
    choice.type = 'button';
    choice.value = option.id;
    choice.setAttribute('aria-label', label);
    const thumbnail = element('img', 'image-option-thumbnail');
    thumbnail.src = option.imageUrl;
    thumbnail.alt = option.name;
    thumbnail.loading = 'lazy';
    thumbnail.decoding = 'async';
    const fallback = element('span', 'image-option-fallback', 'Image could not load');
    fallback.hidden = true;
    thumbnail.addEventListener('error', () => {
      thumbnail.hidden = true;
      fallback.hidden = false;
    });
    choice.append(thumbnail, fallback, element('span', 'image-option-caption', label), element('span', 'image-option-selection'));
    choice.addEventListener('click', () => {
      if (imageControls.disabled) return;
      imageSelections.set(`${imageEntryIndex}:${imageCopyIndex}`, {
        entryIndex: imageEntryIndex, copyIndex: imageCopyIndex.toString(), optionId: option.id,
      });
      imageDirty = true;
      imageSaveButton.disabled = false;
      imageSaveStatus.textContent = '';
      updateImageSelection();
    });
    return choice;
  });
  imageOptions.replaceChildren(...options);
  const status = document.querySelector('#image-match-status');
  status.textContent = entry && !options.length ? 'No images found in the catalog. Edit the card name in your list, then reopen image selection.' : `${options.length} available images`;
  status.classList.toggle('error', !!entry && !options.length);
  previousImageButton.disabled = position <= 1n;
  nextImageButton.disabled = position >= total;
  previousImageButton.hidden = previousImageButton.disabled;
  nextImageButton.hidden = nextImageButton.disabled;
  updateImageSelection();
}

function updateImageSelection() {
  const selectedId = imageSelections.get(`${imageEntryIndex}:${imageCopyIndex}`)?.optionId;
  for (const choice of imageOptions.children) {
    const selected = choice.value === selectedId;
    choice.setAttribute('aria-pressed', String(selected));
    choice.children[3].textContent = selected ? '✓ Selected' : 'Select image';
  }
  const total = imageTotal();
  const position = imageEntryIndex < 0 ? 0n : imageState.entries.slice(0, imageEntryIndex)
    .reduce((count, item) => count + BigInt(item.quantity), 0n) + imageCopyIndex + 1n;
  document.querySelector('#image-progress').textContent = `Card ${position} of ${total} · ${imageSelections.size} of ${total} images selected`;
  imageSaveButton.disabled = imageSelections.size === 0;
}

function moveImageCopy(direction) {
  const quantity = BigInt(imageState.entries[imageEntryIndex].quantity);
  if (direction > 0 && imageCopyIndex + 1n < quantity) imageCopyIndex++;
  else if (direction < 0 && imageCopyIndex > 0n) imageCopyIndex--;
  else {
    let index = imageEntryIndex + direction;
    while (index >= 0 && index < imageState.entries.length && BigInt(imageState.entries[index].quantity) === 0n) index += direction;
    if (index < 0 || index >= imageState.entries.length) return;
    imageEntryIndex = index;
    imageCopyIndex = direction > 0 ? 0n : BigInt(imageState.entries[index].quantity) - 1n;
  }
  renderImageCopy();
}
previousImageButton.addEventListener('click', () => moveImageCopy(-1));
nextImageButton.addEventListener('click', () => moveImageCopy(1));
imageCloseButton.addEventListener('click', () => {
  if (imageLoading || imageControls.disabled) return;
  if (imageDirty && !window.confirm('Discard unsaved image choices?')) return;
  resetImagePicker();
});

imageSaveButton.addEventListener('click', async () => {
  if (!imageState || imageSelections.size === 0 || imageControls.disabled) return;
  imageSaveButton.disabled = true;
  imageControls.disabled = imageCloseButton.disabled = true;
  imageSaveStatus.classList.remove('error');
  imageSaveStatus.textContent = 'Saving image choices…';
  try {
    const result = await listRequest(`/${encodeURIComponent(imageState.listId)}/image-selections`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cardsText: imageState.cardsText, selections: [...imageSelections.values()] }),
    });
    imageDirty = false;
    showSaveToast(`Saved images for ${result.savedCopies} of ${imageTotal()} card copies.`);
    await loadSavedLists();
    imageSaveStatus.textContent = `Saved images for ${result.savedCopies} of ${imageTotal()} card copies to this list.`;
  } catch (error) {
    imageSaveStatus.textContent = error.message;
    imageSaveStatus.classList.add('error');
  } finally {
    imageControls.disabled = imageCloseButton.disabled = false;
    imageSaveButton.disabled = imageSelections.size === 0;
  }
});

loadSavedLists();

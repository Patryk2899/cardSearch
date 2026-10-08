// Fictional demo data. Replace with an API response when backend search exists.
const cards = [
  { name: 'Emberwing Dragon', type: 'Creature', rarity: 'Legendary', set: 'Ashen Skies', symbol: '✦', color: 'ember', description: 'From the last ember, a new legend rises.' },
  { name: 'Tidal Archive', type: 'Land', rarity: 'Rare', set: 'Deepwater', symbol: '≋', color: 'ocean', description: 'Every wave carries a forgotten story.' },
  { name: 'Verdant Guardian', type: 'Creature', rarity: 'Uncommon', set: 'Wildwood', symbol: '❧', color: 'forest', description: 'The oldest trees never stand alone.' },
  { name: 'Astral Compass', type: 'Artifact', rarity: 'Rare', set: 'Starlight', symbol: '✧', color: 'astral', description: 'Find your way beyond the familiar stars.' },
  { name: 'Golden Sanctuary', type: 'Land', rarity: 'Uncommon', set: 'Dawnfall', symbol: '☀', color: 'gold', description: 'A quiet place at the edge of morning.' },
  { name: 'Moonlit Passage', type: 'Spell', rarity: 'Common', set: 'Starlight', symbol: '☽', color: 'moon', description: 'One step into a thousand possibilities.' },
  { name: 'Cinder Spark', type: 'Spell', rarity: 'Common', set: 'Ashen Skies', symbol: 'ϟ', color: 'ember', description: 'Even the smallest spark can change the night.' },
  { name: 'Mossbound Relic', type: 'Artifact', rarity: 'Rare', set: 'Wildwood', symbol: '❖', color: 'forest', description: 'Time leaves its signature in green.' },
];
const search = document.querySelector('#search');
const type = document.querySelector('#type');
const sort = document.querySelector('#sort');
const rarityOrder = { Legendary: 0, Rare: 1, Uncommon: 2, Common: 3 };

function element(tag, className, text) {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function render() {
  const query = search.value.trim().toLocaleLowerCase();
  const matches = cards.filter(card =>
    (!type.value || card.type === type.value) &&
    `${card.name} ${card.set} ${card.description}`.toLocaleLowerCase().includes(query)
  ).sort((a, b) =>
    (sort.value === 'rarity' ? rarityOrder[a.rarity] - rarityOrder[b.rarity] : 0) || a.name.localeCompare(b.name)
  );
  document.querySelector('#cards').replaceChildren(...matches.map(card => {
    const article = element('article', 'card');
    const art = element('div', `card-art ${card.color}`);
    art.setAttribute('aria-hidden', 'true');
    art.append(element('span', 'card-symbol', card.symbol));
    const body = element('div', 'card-body');
    const metadata = element('div', 'card-metadata');
    metadata.append(element('span', '', card.type), element('span', `rarity ${card.rarity.toLowerCase()}`, card.rarity));
    body.append(metadata, element('h3', '', card.name), element('p', 'description', card.description), element('p', 'card-set', card.set));
    article.append(art, body);
    return article;
  }));
  document.querySelector('#result-count').textContent = `${matches.length} ${matches.length === 1 ? 'card' : 'cards'}`;
  document.querySelector('#empty-state').hidden = matches.length > 0;
}
document.querySelector('#search-form').addEventListener('submit', event => event.preventDefault());
search.addEventListener('input', render);
type.addEventListener('change', render);
sort.addEventListener('change', render);
document.querySelector('#reset').addEventListener('click', () => {
  document.querySelector('#search-form').reset();
  render();
  search.focus();
});
render();

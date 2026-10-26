import React from "react";
import { useQuery } from "@tanstack/react-query";
import { availabilityLabel, itemAvailability, request, type Actor, type Photo, type WardrobeItem, type WorkspaceContext, wardrobeItemsPath } from "./api";
import { PhotoImage } from "./site";
import styles from "./site.module.css";

const pageSize = 12;
const contextKey = (context: WorkspaceContext) => context.kind === "own" ? "own" : `${context.kind}:${context.ownerId}`;

export function WardrobeItemPicker({ actor, context, items, selected, onSelectedChange, disabled, maxItems = 50 }: {
  actor: Actor;
  context: WorkspaceContext;
  items: WardrobeItem[] | undefined;
  selected: number[];
  onSelectedChange: (items: number[]) => void;
  disabled: boolean;
  maxItems?: number;
}) {
  const [search, setSearch] = React.useState("");
  const [page, setPage] = React.useState(0);
  const rows = (items ?? []).filter((item) => item.name.toLocaleLowerCase().includes(search.toLocaleLowerCase()));
  const selectedUnavailable = (items ?? []).filter((item) => selected.includes(item.id) && itemAvailability(item) !== "AVAILABLE");

  function toggle(id: number) {
    if (selected.includes(id)) onSelectedChange(selected.filter((itemId) => itemId !== id));
    else if (selected.length < maxItems) onSelectedChange([...selected, id]);
  }

  return <section className={styles.wardrobe}>
    <div className={styles.sectionHeading}><span>01</span><div><h2>Вещи</h2><p>Отметьте от одной до {maxItems} вещей.</p></div>
      <span className={styles.count}>{selected.length} выбрано</span></div>
    {selectedUnavailable.length > 0 && <p className={styles.notice} role="status">В выбранном наборе есть вещи в стирке или убранные. Их можно оставить вручную.</p>}
    <label className={styles.nameField}>ПОИСК ПО НАЗВАНИЮ
      <input value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} disabled={disabled} />
    </label>
    {items?.length === 0 && <p className={styles.state}>В гардеробе пока нет вещей.</p>}
    {items && rows.length === 0 && <p className={styles.state}>Ничего не найдено.</p>}
    <div className={styles.grid}>{rows.slice(page * pageSize, (page + 1) * pageSize).map((item) => <SelectionCard
      key={item.id} actor={actor} context={context} item={item} selected={selected.includes(item.id)}
      toggle={() => toggle(item.id)} disabled={disabled || (!selected.includes(item.id) && selected.length >= maxItems)} />)}</div>
    {rows.length > pageSize && <div className={styles.pages}><span>Страница {page + 1}</span><div>
      <button type="button" disabled={!page || disabled} onClick={() => setPage((value) => value - 1)}>← Назад</button>
      <button type="button" disabled={(page + 1) * pageSize >= rows.length || disabled} onClick={() => setPage((value) => value + 1)}>Далее →</button>
    </div></div>}
  </section>;
}

function SelectionCard({ actor, context, item, selected, toggle, disabled }: { actor: Actor; context: WorkspaceContext; item: WardrobeItem; selected: boolean; toggle: () => void; disabled: boolean }) {
  const base = wardrobeItemsPath(context);
  const photos = useQuery({
    queryKey: ["photos", actor.id, contextKey(context), item.id],
    queryFn: ({ signal }) => request<Photo[]>(`${base}/${item.id}/photos`, actor.id, { signal }),
  });
  return <label className={`${styles.card} ${selected ? styles.selected : ""}`}>
    <span className={styles.image}>{photos.data?.[0]
      ? <PhotoImage actor={actor} item={item} photo={photos.data[0]} itemsBase={base} />
      : <span className={styles.photoEmpty}>{photos.isError ? "Фото не загрузилось" : photos.data ? "Нет фото" : "Загружаем фото…"}</span>}</span>
    <span className={styles.cardInfo}><span className={styles.itemName}>{item.name}</span>
      <small>{item.color} · {item.material}</small><small>{availabilityLabel(item)}</small></span>
    <input type="checkbox" checked={selected} disabled={disabled} onChange={toggle} aria-label={`Выбрать: ${item.name}`} />
  </label>;
}

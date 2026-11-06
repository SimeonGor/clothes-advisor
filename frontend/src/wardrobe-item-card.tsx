import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { availabilityLabel, request, type Actor, type Photo, type WardrobeItem, type WorkspaceContext } from "./api";
import { PhotoImage } from "./site";
import styles from "./site.module.css";

const contextKey = (context: WorkspaceContext) => context.kind === "own"
  ? "own"
  : `${context.kind}:${context.ownerId}`;

export function WardrobeItemCard({ actor, item, base, context, to }: {
  actor: Actor;
  item: WardrobeItem;
  base: string;
  context: WorkspaceContext;
  to?: string;
}) {
  const photos = useQuery({
    queryKey: ["photos", actor.id, contextKey(context), item.id],
    queryFn: ({ signal }) => request<Photo[]>(`${base}/${item.id}/photos`, actor.id, { signal }),
  });
  const content = <>
    <span className={styles.image}>{photos.data?.[0]
      ? <PhotoImage actor={actor} item={item} photo={photos.data[0]} itemsBase={base} />
      : <span className={styles.photoEmpty}>{photos.isError ? "Фото не загрузилось" : photos.data ? "Нет фото" : "Загружаем фото…"}</span>}</span>
    <span className={styles.cardInfo}><span className={styles.itemName}>{item.name}</span>
      <small>{item.color} · {item.material}</small><small>{availabilityLabel(item)}</small></span>
  </>;
  return to ? <Link to={to} className={styles.card}>{content}</Link> : <article className={styles.card}>{content}</article>;
}

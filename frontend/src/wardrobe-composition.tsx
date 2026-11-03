import { useQuery } from "@tanstack/react-query";
import { request, type Actor, type Photo, type WardrobeItem, type WorkspaceContext } from "./api";
import { PhotoImage } from "./site";
import styles from "./collection.module.css";

const contextKey = (context: WorkspaceContext) => context.kind === "own"
  ? "own"
  : `${context.kind}:${context.ownerId}`;

function CompositionTile({ actor, item, context, itemsBase }: {
  actor: Actor;
  item?: WardrobeItem;
  context: WorkspaceContext;
  itemsBase: string;
}) {
  const photos = useQuery({
    queryKey: ["photos", actor.id, contextKey(context), itemsBase, item?.id],
    enabled: !!item,
    queryFn: ({ signal }) => request<Photo[]>(`${itemsBase}/${item?.id}/photos`, actor.id, { signal }),
  });
  const photo = photos.data?.[0];
  return <span className={styles.tile}>
    {item && photo
      ? <PhotoImage actor={actor} item={item} photo={photo} itemsBase={itemsBase} />
      : <span className={styles.emptyPhoto}>{!item ? "Нет вещи" : photos.isError ? "Фото не загрузилось" : photos.data ? "Нет фото" : "Загружаем фото…"}</span>}
  </span>;
}

export function WardrobeComposition({ actor, items, context, itemsBase }: {
  actor: Actor;
  items: WardrobeItem[];
  context: WorkspaceContext;
  itemsBase: string;
}) {
  return <div className={styles.composition} aria-label="Фотографии вещей">
    {items.slice(0, 4).map((item) => <CompositionTile key={item.id} actor={actor} item={item} context={context} itemsBase={itemsBase} />)}
  </div>;
}

import React from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  allPages,
  ApiError,
  canDeleteWardrobePhoto,
  canEditWardrobe,
  resourceErrorMessage,
  request,
  wardrobeItemsPath,
  type Actor,
  type Category,
  type Photo,
  type WardrobeItem,
  type WorkspaceContext,
} from "./api";
import { PhotoImage } from "./site";
import styles from "./site.module.css";

const pageSize = 12;
const maxPhotoSize = 10_000_000;
const allowedPhotoTypes = new Set(["image/jpeg", "image/png"]);
type ItemFields = Pick<WardrobeItem, "name" | "categoryId" | "color" | "material">;

function validateFields(fields: ItemFields, categories: Category[]): string {
  if (!fields.name.trim() || fields.name.trim().length > 300) return "Укажите название длиной до 300 символов.";
  if (!fields.color.trim() || !fields.material.trim()) return "Укажите цвет и материал вещи.";
  if (fields.color.trim().length > 100 || fields.material.trim().length > 100) {
    return "Цвет и материал должны быть не длиннее 100 символов.";
  }
  if (!categories.some((category) => category.id === fields.categoryId)) return "Выберите существующую категорию.";
  return "";
}

function validatePhotos(files: FileList | File[], existingCount = 0): string {
  const selected = Array.from(files);
  if (selected.length + existingCount > 5) return "У вещи может быть не больше пяти фотографий.";
  if (selected.some((file) => !allowedPhotoTypes.has(file.type))) return "Поддерживаются фотографии JPEG и PNG.";
  if (selected.some((file) => file.size > maxPhotoSize)) return "Размер каждой фотографии не должен превышать 10 МБ.";
  return "";
}

function writeError(error: unknown, action: string): string {
  if (error instanceof ApiError && error.status === 409) {
    return "Данные вещи изменились. Обновите страницу и повторите действие.";
  }
  if (error instanceof ApiError && error.status === 404) {
    return "Вещь больше не найдена. Обновите список.";
  }
  if (error instanceof ApiError && error.status === 400) {
    return `${action}. Проверьте поля вещи и повторите попытку.`;
  }
  if (error instanceof ApiError) return `${action} (код ${error.status}). Данные формы сохранены.`;
  return `${action}: ${resourceErrorMessage(error, true)}`;
}

function useWrite(onPending: (pending: boolean) => void) {
  const busyRef = React.useRef(false);
  const [busy, setBusy] = React.useState(false);
  async function run<T>(action: () => Promise<T>): Promise<T | undefined> {
    if (busyRef.current) return undefined;
    busyRef.current = true;
    setBusy(true);
    onPending(true);
    try { return await action(); }
    finally {
      busyRef.current = false;
      setBusy(false);
      onPending(false);
    }
  }
  return { busy, run };
}

function categoriesQuery(actor: Actor) {
  return {
    queryKey: ["categories", actor.id],
    queryFn: ({ signal }: { signal: AbortSignal }) => allPages<Category>("/api/wardrobe/categories", undefined, signal),
  };
}

export function Wardrobe({ actor, context = { kind: "own" }, pending }: {
  actor: Actor;
  context?: WorkspaceContext;
  pending: boolean;
}) {
  const [page, setPage] = React.useState(0);
  const base = wardrobeItemsPath(context);
  const categories = useQuery(categoriesQuery(actor));
  const items = useQuery({
    queryKey: ["wardrobe", actor.id, context, page],
    queryFn: ({ signal }) => request<WardrobeItem[]>(`${base}?page=${page}&size=${pageSize}`, actor.id, { signal }),
  });
  const categoryNames = new Map((categories.data ?? []).map((category) => [category.id, category.name]));
  const writable = canEditWardrobe(context);

  return (
    <main className={styles.main}>
      <header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>ВАШ ГАРДЕРОБ</span></header>
      <section className={styles.intro}>
        <div>
          <p className={styles.eyebrow}>ВАШИ ВЕЩИ</p>
          <h1>Гардероб</h1>
          <p className={styles.muted}>Вещи, из которых складываются ваши образы.</p>
        </div>
        {writable && <Link className={styles.create} to="/wardrobe/new" aria-disabled={pending}>
          Добавить вещь <span>↗</span>
        </Link>}
      </section>
      {categories.isError && <ReadError error={categories.error} retry={() => void categories.refetch()} />}
      {items.isLoading && <p className={styles.state}>Загружаем гардероб…</p>}
      {items.isError && <ReadError error={items.error} retry={() => void items.refetch()} />}
      {items.data?.length === 0 && page === 0 && (
        <section className={styles.empty}>
          <h2>Здесь пока пусто</h2>
          <p>Добавьте первую вещь, чтобы начать собирать гардероб.</p>
          {writable && <Link className={styles.create} to="/wardrobe/new">Добавить первую вещь <span>↗</span></Link>}
        </section>
      )}
      {items.data?.length === 0 && page > 0 && <p className={styles.state}>На этой странице вещей нет.</p>}
      <div className={styles.grid}>
        {items.data?.map((item) => <WardrobeCard
          key={item.id} actor={actor} item={item} category={categoryNames.get(item.categoryId) ?? "Вещь"}
          base={base} />)}
      </div>
      {(items.data && items.data.length > 0 || page > 0) && <div className={styles.pages}>
        <span>Страница {page + 1}</span><div>
          <button type="button" disabled={page === 0 || pending} onClick={() => setPage((n) => n - 1)}>← Назад</button>
          <button type="button" disabled={!items.data || items.data.length < pageSize || pending} onClick={() => setPage((n) => n + 1)}>Далее →</button>
        </div>
      </div>}
    </main>
  );
}

function WardrobeCard({ actor, item, category, base }: {
  actor: Actor; item: WardrobeItem; category: string; base: string;
}) {
  const photos = useQuery({
    queryKey: ["photos", actor.id, item.id],
    queryFn: ({ signal }) => request<Photo[]>(`${base}/${item.id}/photos`, actor.id, { signal }),
  });
  return <Link className={styles.card} to={`/wardrobe/${item.id}`}>
    <span className={styles.image}>
      {photos.data?.[0] ? <PhotoImage actor={actor} item={item} photo={photos.data[0]} itemsBase={base} />
        : photos.isError ? <span className={styles.photoRetry}>Фото не загрузилось</span>
          : <span className={styles.photoEmpty}>{photos.data ? "Нет фото" : "Загружаем фото…"}</span>}
    </span>
    <span className={styles.cardInfo}><span className={styles.itemName}>{item.name}</span>
      <small>{category} · {item.color}</small></span>
  </Link>;
}

function ReadError({ error, retry }: { error: unknown; retry: () => void }) {
  return <div className={styles.error} role="alert"><p>{resourceErrorMessage(error)}</p>
    <button type="button" onClick={retry}>Повторить загрузку</button></div>;
}

export function WardrobeEditor({ actor, onPending, pending }: {
  actor: Actor; onPending: (pending: boolean) => void; pending: boolean;
}) {
  const { itemId } = useParams();
  const editing = itemId !== undefined;
  const id = Number(itemId);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const base = wardrobeItemsPath({ kind: "own" });
  const categories = useQuery(categoriesQuery(actor));
  const itemQuery = useQuery({
    queryKey: ["wardrobe-item", actor.id, id],
    enabled: editing && Number.isInteger(id) && id > 0,
    queryFn: ({ signal }) => request<WardrobeItem>(`${base}/${id}`, actor.id, { signal }),
  });
  const photos = useQuery({
    queryKey: ["photos", actor.id, id],
    enabled: editing && itemQuery.isSuccess,
    queryFn: ({ signal }) => request<Photo[]>(`${base}/${id}/photos`, actor.id, { signal }),
  });
  const [name, setName] = React.useState("");
  const [categoryId, setCategoryId] = React.useState("");
  const [color, setColor] = React.useState("");
  const [material, setMaterial] = React.useState("");
  const [files, setFiles] = React.useState<File[]>([]);
  const [previews, setPreviews] = React.useState<string[]>([]);
  const [failure, setFailure] = React.useState("");
  const [success, setSuccess] = React.useState("");
  const [versionConflict, setVersionConflict] = React.useState(false);
  const [reloadingItem, setReloadingItem] = React.useState(false);
  const [confirmDelete, setConfirmDelete] = React.useState(false);
  const [confirmPhotoId, setConfirmPhotoId] = React.useState<number | null>(null);
  const { busy, run } = useWrite(onPending);
  const draftVersion = React.useRef<number | null>(null);

  React.useEffect(() => {
    const urls = files.map((file) => URL.createObjectURL(file));
    setPreviews(urls);
    return () => urls.forEach((url) => URL.revokeObjectURL(url));
  }, [files]);

  React.useEffect(() => {
    const snapshot = itemQuery.data;
    if (!snapshot || draftVersion.current !== null) return;
    setName(snapshot.name);
    setCategoryId(String(snapshot.categoryId));
    setColor(snapshot.color);
    setMaterial(snapshot.material);
    draftVersion.current = snapshot.version;
  }, [itemQuery.data]);

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["wardrobe", actor.id] });
    void queryClient.invalidateQueries({ queryKey: ["items", actor.id] });
    void queryClient.invalidateQueries({ queryKey: ["photos", actor.id] });
  }

  async function reloadItem() {
    setReloadingItem(true);
    try {
      const result = await itemQuery.refetch();
      if (!result.data || result.isError) {
        setFailure("Не удалось обновить данные. Ваши значения остались в форме.");
        return;
      }
      const snapshot = result.data;
      setName(snapshot.name);
      setCategoryId(String(snapshot.categoryId));
      setColor(snapshot.color);
      setMaterial(snapshot.material);
      draftVersion.current = snapshot.version;
      setVersionConflict(false);
      setFailure("");
      setSuccess("Данные обновлены.");
    } finally {
      setReloadingItem(false);
    }
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const fields: ItemFields = { name: name.trim(), categoryId: Number(categoryId), color: color.trim(), material: material.trim() };
    const fieldError = validateFields(fields, categories.data ?? []);
    if (fieldError) { setFailure(fieldError); return; }
    if (!editing) {
      const photoError = validatePhotos(files);
      if (photoError) { setFailure(photoError); return; }
    }
    setFailure("");
    setSuccess("");
    await run(async () => {
      try {
        if (editing) {
          const version = draftVersion.current;
          if (version === null) return;
          const updated = await request<WardrobeItem>(`${base}/${id}`, actor.id, {
            method: "PUT", body: JSON.stringify({ ...fields, version }),
          });
          setName(updated.name);
          setCategoryId(String(updated.categoryId));
          setColor(updated.color);
          setMaterial(updated.material);
          draftVersion.current = updated.version;
          queryClient.setQueryData(["wardrobe-item", actor.id, id], updated);
          setFailure("");
          setVersionConflict(false);
          setSuccess("Сохранено.");
          refresh();
        } else {
          const body = files.length ? (() => {
            const form = new FormData();
            form.append("item", new Blob([JSON.stringify(fields)], { type: "application/json" }));
            files.forEach((file) => form.append("photos", file));
            return form;
          })() : JSON.stringify(fields);
          const created = await request<WardrobeItem>(base, actor.id, { method: "POST", body });
          refresh();
          navigate(`/wardrobe/${created.id}`);
        }
      } catch (error) {
        setFailure(writeError(error, "Не удалось сохранить вещь"));
        setVersionConflict(editing && error instanceof ApiError && error.status === 409);
      }
    });
  }

  async function uploadPhotos(selected: File[]) {
    const photoError = validatePhotos(selected, photos.data?.length ?? 0);
    if (photoError) { setFailure(photoError); return; }
    if (!selected.length) return;
    setFailure("");
    setSuccess("");
    await run(async () => {
      try {
        const form = new FormData();
        selected.forEach((file) => form.append("photos", file));
        await request<Photo[]>(`${base}/${id}/photos`, actor.id, { method: "POST", body: form });
        await Promise.all([photos.refetch(), queryClient.invalidateQueries({ queryKey: ["photos", actor.id] })]);
      } catch (error) { setFailure(writeError(error, "Не удалось загрузить фото")); }
    });
  }

  async function removePhoto(photo: Photo) {
    await run(async () => {
      try {
        await request<void>(`${base}/${id}/photos/${photo.id}`, actor.id, { method: "DELETE" });
        await photos.refetch();
        await queryClient.invalidateQueries({ queryKey: ["photos", actor.id] });
        setConfirmPhotoId(null);
      } catch (error) { setFailure(writeError(error, "Не удалось удалить фото")); }
    });
  }

  async function removeItem() {
    const version = draftVersion.current;
    if (version === null) return;
    await run(async () => {
      try {
        await request<void>(`${base}/${id}?version=${version}`, actor.id, { method: "DELETE" });
        refresh();
        navigate("/wardrobe");
      } catch (error) {
        if (error instanceof ApiError && error.status === 409) {
          setFailure("Вещь используется в образе или была изменена. Обновите данные.");
          setVersionConflict(true);
        } else setFailure(writeError(error, "Не удалось удалить вещь"));
        setConfirmDelete(false);
      }
    });
  }

  const fieldValues = { name, categoryId, color, material };
  if (editing && (!Number.isInteger(id) || id <= 0)) return <main className={styles.main}><p role="alert">Вещь не найдена.</p><Link to="/wardrobe">К гардеробу</Link></main>;
  if (editing && itemQuery.isError) return <main className={styles.main}><ReadError error={itemQuery.error} retry={() => void itemQuery.refetch()} /></main>;
  if (editing && (itemQuery.isLoading || !itemQuery.data || draftVersion.current === null)) return <main className={styles.main}><p className={styles.state}>Загружаем вещь…</p></main>;
  return <main className={styles.main}>
    <header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><Link to="/wardrobe" aria-disabled={pending}
      onClick={(event) => pending && event.preventDefault()}>← Гардероб</Link></header>
    <section className={styles.intro}><div><p className={styles.eyebrow}>{editing ? "ВАША ВЕЩЬ" : "НОВАЯ ВЕЩЬ"}</p>
      <h1>{editing ? "Изменить вещь" : "Добавить вещь"}</h1></div></section>
    {failure && <div className={styles.error} role="alert"><p>{failure}</p>
      {versionConflict && <button type="button" disabled={pending || reloadingItem} onClick={() => void reloadItem()}>
        {reloadingItem ? "Обновляем…" : "Обновить данные"}
      </button>}
    </div>}
    {success && <p role="status" className={styles.success}>{success}</p>}
    {categories.isError && <ReadError error={categories.error} retry={() => void categories.refetch()} />}
    <form className={styles.itemForm} onSubmit={(event) => void submit(event)}>
      <label className={styles.nameField}>НАЗВАНИЕ
        <input required maxLength={300} value={fieldValues.name} onChange={(event) => { setName(event.target.value); setSuccess(""); }} disabled={pending} />
      </label>
      <label className={styles.nameField}>КАТЕГОРИЯ
        <select required value={fieldValues.categoryId} onChange={(event) => { setCategoryId(event.target.value); setSuccess(""); }} disabled={pending || categories.isLoading || categories.isError}>
          <option value="">Выберите категорию</option>{categories.data?.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
        </select>
      </label>
      <label className={styles.nameField}>ЦВЕТ
        <input required maxLength={100} value={fieldValues.color} onChange={(event) => { setColor(event.target.value); setSuccess(""); }} disabled={pending} />
      </label>
      <label className={styles.nameField}>МАТЕРИАЛ
        <input required maxLength={100} value={fieldValues.material} onChange={(event) => { setMaterial(event.target.value); setSuccess(""); }} disabled={pending} />
      </label>
      {!editing && <label className={styles.nameField}>ФОТОГРАФИИ · JPEG ИЛИ PNG, ДО 10 МБ КАЖДАЯ
        <input type="file" accept="image/jpeg,image/png" multiple disabled={pending}
          onChange={(event) => { setFiles(Array.from(event.target.files ?? [])); setFailure(""); }} />
        <small>До пяти фото. Можно добавить фотографии позже.</small>
        {files.length > 0 && <><span>{files.map((file) => file.name).join(", ")}</span>
          <div className={styles.grid}>{files.map((file, index) => <figure className={styles.card} key={`${file.name}-${index}`}>
            <span className={styles.image}>
              {previews[index] && <img className={styles.photo} src={previews[index]} alt={`Предпросмотр: ${file.name}`} />}
            </span>
            <figcaption className={styles.photoCaption}>{file.name}</figcaption>
          </figure>)}</div>
        </>}
      </label>}
      <div className={styles.submitRow}><Link className={styles.textButton} to="/wardrobe" aria-disabled={pending}
        onClick={(event) => pending && event.preventDefault()}>Отмена</Link>
        <button className={styles.create} type="submit" disabled={pending || categories.isLoading || categories.isError}>
          {pending ? "Сохраняем…" : editing ? "Сохранить изменения" : "Добавить вещь"}<span>↗</span>
        </button>
      </div>
    </form>
    {editing && itemQuery.data && <section className={styles.photoSection}>
      <h2>Фотографии</h2>
      <p className={styles.muted}>До пяти фотографий JPEG или PNG, не более 10 МБ каждая.</p>
      {photos.isLoading && <p>Загружаем фотографии…</p>}
      {photos.isError && <ReadError error={photos.error} retry={() => void photos.refetch()} />}
      {photos.data?.length === 0 && <p className={styles.state}>У этой вещи пока нет фотографий.</p>}
      <div className={styles.grid}>{photos.data?.map((photo) => <article className={styles.card} key={photo.id}>
        <span className={styles.image}><PhotoImage actor={actor} item={itemQuery.data!} photo={photo} /></span>
        {canDeleteWardrobePhoto({ kind: "own" }) && (confirmPhotoId === photo.id
          ? <div role="group" aria-label="Подтверждение удаления фото">
              <span>Удалить это фото?</span>
              <button type="button" disabled={pending} onClick={() => void removePhoto(photo)}>Да, удалить</button>
              <button type="button" disabled={pending} onClick={() => setConfirmPhotoId(null)}>Оставить</button>
            </div>
          : <button type="button" className={styles.photoRetry} disabled={pending} onClick={() => setConfirmPhotoId(photo.id)}>Удалить фото</button>)}
      </article>)}</div>
      {photos.isSuccess && photos.data.length < 5 && <label className={styles.nameField}>ДОБАВИТЬ ФОТО
        <input type="file" accept="image/jpeg,image/png" multiple disabled={pending || !photos.isSuccess}
          onChange={(event) => { const selected = Array.from(event.target.files ?? []); event.currentTarget.value = ""; void uploadPhotos(selected); }} />
      </label>}
    </section>}
    {editing && <section className={styles.deleteSection}>
      {!confirmDelete ? <button className={styles.deleteButton} type="button" disabled={pending} onClick={() => setConfirmDelete(true)}>Удалить вещь</button>
        : <div role="group" aria-label="Подтверждение удаления вещи"><p>Удалить «{itemQuery.data?.name}»? Это действие нельзя отменить.</p>
          <button type="button" disabled={pending} onClick={() => void removeItem()}>Да, удалить</button>
          <button type="button" disabled={pending} onClick={() => setConfirmDelete(false)}>Оставить вещь</button></div>}
    </section>}
  </main>;
}

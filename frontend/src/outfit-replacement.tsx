import React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  allPages,
  ApiError,
  mutationErrorMessage,
  resourceErrorMessage,
  outfitsPath,
  type Actor,
  type Outfit,
  type WardrobeItem,
} from "./api";
import { createRequest, NewAttempt } from "./idempotency";
import styles from "./site.module.css";

export function OutfitReplacement({ actor, outfit, sourceItems, onPending, pending }: {
  actor: Actor;
  outfit: Outfit;
  sourceItems: WardrobeItem[];
  onPending: (value: boolean) => void;
  pending: boolean;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const path = `${outfitsPath({ kind: "own" })}/${outfit.id}/ai-replacements`;
  const sourceIds = new Set(outfit.itemIds);
  const [name, setName] = React.useState(outfit.name);
  const [replaced, setReplaced] = React.useState<number[]>([]);
  const [manual, setManual] = React.useState(false);
  const [candidates, setCandidates] = React.useState<number[]>([]);
  const [failure, setFailure] = React.useState("");
  const busy = React.useRef(false);
  const items = useQuery({
    queryKey: ["outfit-replacement-candidates", actor.id],
    enabled: manual,
    queryFn: ({ signal }) => allPages<WardrobeItem>("/api/wardrobe/items", actor.id, signal),
  });
  const availableCandidates = (items.data ?? []).filter((item) => !sourceIds.has(item.id));

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy.current || pending) return;

    const cleanName = name.trim();
    if (!cleanName || cleanName.length > 300 || replaced.length === 0) {
      setFailure("Укажите название до 300 символов и отметьте хотя бы одну вещь для замены.");
      return;
    }
    if (manual && candidates.length === 0) {
      setFailure("Для ручного выбора отметьте хотя бы одну вещь-кандидат.");
      return;
    }
    if (manual && !items.data) return;

    const body: { name: string; replaceItemIds: number[]; candidateItemIds?: number[] } = {
      name: cleanName,
      replaceItemIds: replaced,
    };
    if (manual) body.candidateItemIds = candidates;

    busy.current = true;
    onPending(true);
    setFailure("");
    try {
      const saved = await createRequest<Outfit>(path, actor.id, JSON.stringify(body));
      await queryClient.invalidateQueries({ queryKey: ["outfits", actor.id, "own"] });
      navigate(`/outfits/${saved.id}`);
    } catch (error) {
      setFailure(error instanceof ApiError && error.status === 400
        ? "Проверьте название, выбор вещей и наличие фото. Для образа доступно не более 20 вещей с фото."
        : mutationErrorMessage(error));
    } finally {
      busy.current = false;
      onPending(false);
    }
  }

  return (
    <section className={styles.photoSection} aria-labelledby="replacement-heading">
      <h2 id="replacement-heading">Заменить вещи через AI</h2>
      <p className={styles.muted}>Отметьте вещи, которые нужно заменить. Остальные останутся в образе. Предел — 20 вещей с фото.</p>
      <form onSubmit={(event) => void submit(event)}>
        <label className={styles.nameField}>НАЗВАНИЕ НОВОГО ОБРАЗА
          <input required maxLength={300} value={name} onChange={(event) => setName(event.target.value)} disabled={pending} />
        </label>
        <fieldset className={styles.replacementChoices} disabled={pending}>
          <legend>Что заменить</legend>
          {sourceItems.map((item) => (
            <label key={item.id}>
              <input
                type="checkbox"
                checked={replaced.includes(item.id)}
                onChange={() => setReplaced((old) => old.includes(item.id)
                  ? old.filter((id) => id !== item.id)
                  : [...old, item.id])}
              />
              {item.name}
            </label>
          ))}
        </fieldset>
        <label className={styles.replacementMode}>
          <input type="checkbox" checked={manual} onChange={(event) => setManual(event.target.checked)} disabled={pending} />
          Выбрать вещи-кандидаты вручную
        </label>
        {manual && <div className={styles.replacementCandidates}>
          <p className={styles.muted}>Оставшиеся вещи исходного образа добавит сервер. Выберите хотя бы одну вещь; заменяемые вещи исключены.</p>
          {items.isLoading && <p className={styles.state}>Загружаем гардероб…</p>}
          {items.isError && <div className={styles.error} role="alert">
            <p>{resourceErrorMessage(items.error)}</p>
            <button type="button" disabled={pending} onClick={() => void items.refetch()}>Повторить загрузку</button>
          </div>}
          {items.data && availableCandidates.length === 0 && <p className={styles.state}>Нет других вещей в гардеробе.</p>}
          {availableCandidates.map((item) => (
            <label key={item.id}>
              <input
                type="checkbox"
                checked={candidates.includes(item.id)}
                disabled={pending}
                onChange={() => setCandidates((old) => old.includes(item.id)
                  ? old.filter((id) => id !== item.id)
                  : [...old, item.id])}
              />
              {item.name}
            </label>
          ))}
        </div>}
        {failure && <div role="alert" className={styles.error}>{failure}</div>}
        {failure && <NewAttempt actorId={actor.id} path={path} disabled={pending} onReset={() => setFailure("")} />}
        <div className={styles.submitRow}>
          <p>Исходный образ останется без изменений. Запрос не повторяется автоматически.</p>
          <button className={styles.create} type="submit" disabled={pending || !sourceItems.length || (manual && !items.data)}>
            Создать новый образ <span>↗</span>
          </button>
        </div>
      </form>
    </section>
  );
}

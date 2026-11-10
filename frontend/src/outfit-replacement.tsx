import React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  allPages,
  ApiError,
  mutationErrorMessage,
  resourceErrorMessage,
  type Actor,
  type Outfit,
  type WardrobeItem,
  availabilityLabel,
  itemAvailability,
} from "./api";
import { AiJobStatus, ambiguousAiPostFailure, useAiJob } from "./ai-jobs";
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
  const sourceIds = new Set(outfit.itemIds);
  const [name, setName] = React.useState(outfit.name);
  const [replaced, setReplaced] = React.useState<number[]>([]);
  const [manual, setManual] = React.useState(false);
  const [candidates, setCandidates] = React.useState<number[]>([]);
  const [failure, setFailure] = React.useState("");
  const [frozenBody, setFrozenBody] = React.useState<string | null>(null);
  const busy = React.useRef(false);
  const ai = useAiJob(actor.id, "replacement", outfit.id);
  React.useEffect(() => {
    if (ai.resultPath) {
      void queryClient.invalidateQueries({ queryKey: ["outfits", actor.id, "own"] });
      ai.clearTerminal();
      navigate(ai.resultPath);
    }
  }, [ai.resultPath, actor.id, navigate, queryClient]);
  const items = useQuery({
    queryKey: ["outfit-replacement-candidates", actor.id],
    enabled: manual,
    queryFn: ({ signal }) => allPages<WardrobeItem>("/api/wardrobe/items", actor.id, signal),
  });
  const candidateOptions = (items.data ?? []).filter((item) => !sourceIds.has(item.id));
  const unavailableSources = sourceItems.filter((item) => itemAvailability(item) !== "AVAILABLE");
  const selectedUnavailable = candidates.filter((id) => {
    const item = items.data?.find((row) => row.id === id);
    return item && itemAvailability(item) !== "AVAILABLE";
  });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy.current || pending || ai.active) return;
    if (frozenBody === null && unavailableSources.some((item) => !replaced.includes(item.id))) {
      setFailure("Отметьте недоступные вещи исходного образа к замене.");
      return;
    }
    if (frozenBody === null && manual && selectedUnavailable.length) {
      setFailure("Снимите выбор с вещей-кандидатов, которые сейчас в стирке или убраны.");
      return;
    }

    const cleanName = name.trim();
    if (frozenBody === null && (!cleanName || cleanName.length > 300 || replaced.length === 0)) {
      setFailure("Укажите название до 300 символов и отметьте хотя бы одну вещь для замены.");
      return;
    }
    if (frozenBody === null && manual && candidates.length === 0) {
      setFailure("Для ручного выбора отметьте хотя бы одну вещь-кандидат.");
      return;
    }
    if (frozenBody === null && manual && !items.data) return;

    let body = frozenBody;
    if (!body) {
      const request: { name: string; replaceItemIds: number[]; candidateItemIds?: number[] } = {
        name: cleanName,
        replaceItemIds: replaced,
      };
      if (manual) request.candidateItemIds = candidates;
      body = JSON.stringify(request);
    }

    busy.current = true;
    onPending(true);
    setFailure("");
    try {
      const outcome = await ai.submit(body);
      if (outcome === false) return;
      if (outcome !== true) {
        if (ambiguousAiPostFailure(outcome.error)) setFrozenBody(body);
        throw outcome.error;
      }
    } catch (error) {
      setFailure(error instanceof ApiError && error.status === 400
        ? "Проверьте название, выбор вещей и наличие фото. Для образа доступно не более 20 вещей с фото."
        : mutationErrorMessage(error));
    } finally {
      busy.current = false;
      onPending(false);
    }
  }

  function beginNewAttempt() {
    if (ai.startNewAttempt()) {
      setFrozenBody(null);
      setFailure("");
    }
  }

  return (
    <section className={styles.photoSection} aria-labelledby="replacement-heading">
      <h2 id="replacement-heading">Заменить вещи через AI</h2>
      <p className={styles.muted}>Отметьте вещи, которые нужно заменить. Остальные останутся в образе. Предел — 20 вещей с фото.</p>
      <form onSubmit={(event) => void submit(event)}>
        <label className={styles.nameField}>НАЗВАНИЕ НОВОГО ОБРАЗА
          <input required maxLength={300} value={name} onChange={(event) => setName(event.target.value)} disabled={pending || frozenBody !== null} />
        </label>
        <fieldset className={styles.replacementChoices} disabled={pending || frozenBody !== null}>
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
              {item.name} · {availabilityLabel(item)}
            </label>
          ))}
        </fieldset>
        <label className={styles.replacementMode}>
          <input type="checkbox" checked={manual} onChange={(event) => setManual(event.target.checked)} disabled={pending || frozenBody !== null} />
          Выбрать вещи-кандидаты вручную
        </label>
        {manual && <div className={styles.replacementCandidates}>
          <p className={styles.muted}>Оставшиеся вещи исходного образа добавит сервер. Выберите хотя бы одну вещь; заменяемые вещи исключены.</p>
          {items.isLoading && <p className={styles.state}>Загружаем гардероб…</p>}
          {items.isError && <div className={styles.error} role="alert">
            <p>{resourceErrorMessage(items.error)}</p>
            <button type="button" disabled={pending} onClick={() => void items.refetch()}>Повторить загрузку</button>
          </div>}
          {items.data && candidateOptions.length === 0 && <p className={styles.state}>Нет других вещей в гардеробе.</p>}
          {candidateOptions.map((item) => (
            <label key={item.id}>
              <input
                type="checkbox"
                checked={candidates.includes(item.id)}
                disabled={pending || frozenBody !== null || (!candidates.includes(item.id) && itemAvailability(item) !== "AVAILABLE")}
                onChange={() => setCandidates((old) => old.includes(item.id)
                  ? old.filter((id) => id !== item.id)
                  : [...old, item.id])}
              />
              {item.name} · {availabilityLabel(item)}
            </label>
          ))}
        </div>}
        {failure && <div role="alert" className={styles.error}>{failure}</div>}
        <AiJobStatus job={ai.job} failure={ai.active ? ai.failure : ""} active={Boolean(ai.active)} canStartNew={ai.canStartNew} canRetryPolling={ai.canRetryPolling} resultPath={ai.resultPath} onRetry={ai.retryPolling} onUnknown={beginNewAttempt} />
        <div className={styles.submitRow}>
          <p>Исходный образ останется без изменений. Запрос не повторяется автоматически.</p>
          <button className={styles.create} type="submit" disabled={pending || ai.busy || ai.active !== null || !sourceItems.length || (frozenBody === null && manual && !items.data)}>
            {frozenBody === null ? "Создать новый образ" : "Повторить ту же попытку"} <span>↗</span>
          </button>
        </div>
      </form>
    </section>
  );
}

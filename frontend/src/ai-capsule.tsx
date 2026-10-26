import React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { allPages, ApiError, itemAvailability, resourceErrorMessage, type Actor, type Capsule, type Precipitation, type WardrobeItem, wardrobeItemsPath } from "./api";
import { createRequest, NewAttempt } from "./idempotency";
import { WardrobeItemPicker } from "./wardrobe-item-picker";
import styles from "./site.module.css";

const capsulesKey = (actorId: number) => ["capsules", actorId] as const;

function Denied() {
  return <main className={styles.main}><div className={styles.error} role="alert">Капсулы доступны только аккаунтам USER.</div></main>;
}

function ReadError({ error, retry, disabled }: { error: unknown; retry: () => void; disabled: boolean }) {
  return <div className={styles.error} role="alert"><p>{resourceErrorMessage(error)}</p>
    <button type="button" disabled={disabled} onClick={retry}>Повторить загрузку</button></div>;
}

type AiCapsuleBody = {
  name: string;
  weather: { temperatureC: number; windSpeedMps: number; precipitationTypeId: number };
  candidateItemIds?: number[];
  occasion?: string;
  preferredStyle?: string;
};

export function AiCapsuleEditor({ actor, onPending, pending }: { actor: Actor; onPending: (value: boolean) => void; pending: boolean }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [name, setName] = React.useState("");
  const [occasion, setOccasion] = React.useState("");
  const [preferredStyle, setPreferredStyle] = React.useState("");
  const [temperature, setTemperature] = React.useState("15");
  const [wind, setWind] = React.useState("0");
  const [precipitationId, setPrecipitationId] = React.useState("");
  const [manualCandidates, setManualCandidates] = React.useState(false);
  const [selected, setSelected] = React.useState<number[]>([]);
  const [failure, setFailure] = React.useState("");
  const [attemptFailed, setAttemptFailed] = React.useState(false);
  const [wardrobeReloadRequired, setWardrobeReloadRequired] = React.useState(false);
  const [wardrobeReloaded, setWardrobeReloaded] = React.useState(false);
  const [wardrobeReloading, setWardrobeReloading] = React.useState(false);
  const [weatherReloading, setWeatherReloading] = React.useState(false);
  const attemptBody = React.useRef<AiCapsuleBody | null>(null);
  const busy = React.useRef(false);
  const types = useQuery({
    queryKey: ["precipitation", actor.id],
    enabled: actor.role === "USER",
    queryFn: ({ signal }) => allPages<Precipitation>("/api/weather/precipitation-types", undefined, signal),
  });
  const items = useQuery({
    queryKey: ["capsule-ai-items", actor.id],
    enabled: actor.role === "USER",
    queryFn: ({ signal }) => allPages<WardrobeItem>(wardrobeItemsPath({ kind: "own" }), actor.id, signal),
  });
  if (actor.role !== "USER") return <Denied />;

  const readsReady = types.isSuccess && items.isSuccess && !types.isFetching && !items.isFetching;
  const newAttemptReady = readsReady && (!wardrobeReloadRequired || wardrobeReloaded) && !pending && !busy.current;

  async function reloadWardrobe() {
    if (pending || busy.current) return;
    busy.current = true;
    setWardrobeReloading(true);
    onPending(true);
    setFailure("");
    try {
      const result = await items.refetch();
      if (!result.isSuccess) {
        setFailure(`Не удалось обновить гардероб. ${resourceErrorMessage(result.error, true)} Черновик сохранён.`);
        setWardrobeReloaded(false);
        return;
      }
      const freshIds = new Set(result.data.map((item) => item.id));
      const removed = selected.some((id) => !freshIds.has(id));
      setFailure(removed
        ? "Гардероб обновлён: некоторые выбранные вещи больше недоступны. Проверьте выбор перед новой попыткой."
        : "Гардероб обновлён. Проверьте выбор перед новой попыткой.");
      setWardrobeReloaded(true);
    } finally {
      busy.current = false;
      setWardrobeReloading(false);
      onPending(false);
    }
  }

  async function reloadWeather() {
    if (pending || busy.current) return;
    busy.current = true;
    setWeatherReloading(true);
    onPending(true);
    try {
      await types.refetch();
    } finally {
      busy.current = false;
      setWeatherReloading(false);
      onPending(false);
    }
  }

  function buildBody(): AiCapsuleBody | null {
    const cleanName = name.trim();
    const cleanOccasion = occasion.trim();
    const cleanStyle = preferredStyle.trim();
    const temp = Number(temperature);
    const breeze = Number(wind);
    const precipitationTypeId = Number(precipitationId);
    const validPrecipitation = types.data?.some((type) => type.id === precipitationTypeId);
    const unavailable = selected.some((id) => !items.data?.some((item) => item.id === id && itemAvailability(item) === "AVAILABLE"));
    if (!cleanName || cleanName.length > 300 || cleanOccasion.length > 300 || cleanStyle.length > 300
      || !temperature.trim() || !wind.trim() || !Number.isFinite(temp) || !Number.isFinite(breeze) || breeze < 0
      || !validPrecipitation || (manualCandidates && (selected.length < 1 || selected.length > 20 || unavailable))) {
      setFailure("Проверьте название, погоду, повод, стиль и выбор от одной до двадцати доступных вещей.");
      return null;
    }
    const body: AiCapsuleBody = {
      name: cleanName,
      weather: { temperatureC: temp, windSpeedMps: breeze, precipitationTypeId },
    };
    if (manualCandidates) body.candidateItemIds = [...selected];
    if (cleanOccasion) body.occasion = cleanOccasion;
    if (cleanStyle) body.preferredStyle = cleanStyle;
    return body;
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy.current || pending || !readsReady || wardrobeReloadRequired) return;
    if (!attemptBody.current) {
      const body = buildBody();
      if (!body) return;
      attemptBody.current = body;
    }
    busy.current = true;
    onPending(true);
    setFailure("");
    try {
      const saved = await createRequest<Capsule>("/api/me/capsules/ai", actor.id, JSON.stringify(attemptBody.current));
      queryClient.setQueryData(["capsule", actor.id, saved.id], saved);
      await queryClient.invalidateQueries({ queryKey: capsulesKey(actor.id) });
      navigate(`/capsules/${saved.id}`);
    } catch (error) {
      const domainConflict = error instanceof ApiError && error.status === 409 && !error.code?.startsWith("idempotency_");
      if (domainConflict) {
        setFailure("Данные гардероба изменились. Обновите гардероб, проверьте выбор и подтвердите новую попытку.");
        setWardrobeReloadRequired(true);
        setWardrobeReloaded(false);
      } else if (error instanceof ApiError && error.status === 503) {
        setFailure("Подбор сейчас занят или недоступен. Попробуйте повторить ту же попытку позже.");
      } else if (error instanceof ApiError && error.status === 422) {
        setFailure("Не удалось подобрать капсулу по этим условиям. Измените вводные и начните новую попытку.");
      } else if (error instanceof ApiError && (error.status === 502 || error.status === 504)) {
        setFailure("AI-сервис не подтвердил подбор. Повторите ту же попытку позже.");
      } else {
        setFailure(`Не удалось получить подтверждение. ${resourceErrorMessage(error, true)} Черновик сохранён.`);
      }
      setAttemptFailed(true);
    } finally {
      busy.current = false;
      onPending(false);
    }
  }

  function beginNewAttempt() {
    const availableIds = new Set((items.data ?? []).filter((item) => itemAvailability(item) === "AVAILABLE").map((item) => item.id));
    setSelected((current) => current.filter((id) => availableIds.has(id)));
    if (!types.data?.some((type) => type.id === Number(precipitationId))) setPrecipitationId("");
    attemptBody.current = null;
    setAttemptFailed(false);
    setWardrobeReloadRequired(false);
    setWardrobeReloaded(false);
    setFailure("Проверьте погоду и выбор вещей перед новой попыткой.");
  }

  return <main className={styles.main}>
    <header className={styles.top}><span>ПЕРСОНАЛЬНЫЙ СТИЛЬ</span><span>AI-КАПСУЛА</span></header>
    <section className={styles.intro}><div><p className={styles.eyebrow}>ПОДБОР С ИИ</p><h1>Капсула для вас</h1>
      <p className={styles.muted}>Задайте условия, а подбор учтёт предпочтения вашего профиля.</p>
      <p><Link to="/preferences">Настроить предпочтения профиля ↗</Link></p></div>
      <Link className={styles.textButton} to="/capsules">← К капсулам</Link>
    </section>
    <form onSubmit={(event) => void submit(event)}>
      <section className={styles.formTop}>
        <label className={styles.nameField}>НАЗВАНИЕ КАПСУЛЫ
          <input required maxLength={300} value={name} onChange={(event) => setName(event.target.value)} disabled={pending || attemptFailed} />
        </label>
        <label className={styles.nameField}>ПОВОД (НЕОБЯЗАТЕЛЬНО)
          <input maxLength={300} value={occasion} onChange={(event) => setOccasion(event.target.value)} disabled={pending || attemptFailed} />
        </label>
        <label className={styles.nameField}>ЖЕЛАЕМЫЙ СТИЛЬ (НЕОБЯЗАТЕЛЬНО)
          <input maxLength={300} value={preferredStyle} onChange={(event) => setPreferredStyle(event.target.value)} disabled={pending || attemptFailed} />
        </label>
        <div className={styles.weatherFields}>
          <label>ТЕМПЕРАТУРА °C
            <input type="number" step="any" required value={temperature} onChange={(event) => setTemperature(event.target.value)} disabled={pending || attemptFailed} />
          </label>
          <label>ВЕТЕР м/с
            <input type="number" step="any" min="0" required value={wind} onChange={(event) => setWind(event.target.value)} disabled={pending || attemptFailed} />
          </label>
          <label>ОСАДКИ
            <select required value={precipitationId} onChange={(event) => setPrecipitationId(event.target.value)} disabled={pending || attemptFailed || !types.data || types.isFetching || types.isError}>
              <option value="">Выберите</option>
              {types.isSuccess && !types.isError && !types.isFetching && types.data.map((type) => <option key={type.id} value={type.id}>{type.name}</option>)}
            </select>
          </label>
        </div>
        {types.isLoading && <p className={styles.state}>Загружаем справочник осадков…</p>}
        {types.isError && <ReadError error={types.error} disabled={pending || busy.current || weatherReloading} retry={() => void reloadWeather()} />}
        {items.isLoading && <p className={styles.state}>Загружаем гардероб…</p>}
        {items.isError && !wardrobeReloadRequired && <ReadError error={items.error} disabled={pending || busy.current || wardrobeReloading} retry={() => void reloadWardrobe()} />}
        {wardrobeReloadRequired && <div role="status" className={styles.state}>
          <p>{wardrobeReloading ? "Обновляем гардероб…" : "Для продолжения обновите гардероб."}</p>
          <button type="button" disabled={pending || wardrobeReloading || (wardrobeReloaded && items.isSuccess)} onClick={() => void reloadWardrobe()}>
            {wardrobeReloaded && items.isSuccess ? "Гардероб обновлён" : "Обновить гардероб"}
          </button>
        </div>}
      </section>
      <section className={styles.wardrobe}>
        <div className={styles.sectionHeading}><span>01</span><div><h2>Гардероб</h2>
          <p>{manualCandidates ? "Подбор ограничен выбранными вещами." : "По умолчанию ИИ рассматривает весь подходящий гардероб."}</p></div></div>
        <label><input type="checkbox" checked={manualCandidates} disabled={pending || attemptFailed || wardrobeReloadRequired}
          onChange={(event) => { setManualCandidates(event.target.checked); setSelected([]); }} /> Выбрать вещи-кандидаты вручную</label>
        <p className={styles.notice}>ИИ рассматривает от 1 до 20 доступных вещей с фотографиями. Выберите кандидатов вручную, чтобы сузить подбор. Вещи без фото не участвуют.</p>
        <p className={styles.count}>{manualCandidates ? `Выбрано: ${selected.length} из 20` : "Весь гардероб"}</p>
        {items.isSuccess && !items.isError && !items.isFetching && !wardrobeReloadRequired && manualCandidates && <WardrobeItemPicker
          actor={actor} context={{ kind: "own" }} items={items.data.filter((item) => itemAvailability(item) === "AVAILABLE")} selected={selected} onSelectedChange={setSelected}
          maxItems={20} disabled={pending || attemptFailed || !readsReady} />}
      </section>
      {failure && <div role="alert" className={styles.error}>{failure}
        {attemptFailed && <NewAttempt actorId={actor.id} path="/api/me/capsules/ai" disabled={!newAttemptReady} onReset={beginNewAttempt} />}
      </div>}
      <div className={styles.submitRow}><p>Запрос не повторяется автоматически.</p>
        <button className={styles.create} type="submit" disabled={pending || !readsReady || wardrobeReloadRequired}>
          {attemptFailed ? "Повторить ту же попытку" : "Создать AI-капсулу"} <span>↗</span>
        </button>
      </div>
    </form>
  </main>;
}

export function CapsuleAiContext({ actor, capsule }: { actor: Actor; capsule: Capsule }) {
  const weather = capsule.weather;
  const precipitation = useQuery({
    queryKey: ["precipitation", actor.id],
    enabled: capsule.source === "AI" && !!weather,
    queryFn: ({ signal }) => allPages<Precipitation>("/api/weather/precipitation-types", undefined, signal),
  });
  if (capsule.source !== "AI") return null;
  const precipitationType = weather && precipitation.data?.find((type) => type.id === weather.precipitationTypeId);
  return <section className={styles.formTop} aria-label="Данные AI-подбора">
    <p className={styles.eyebrow}>ПОДБОР С ИИ</p>
    {weather && <p>{weather.temperatureC} °C · ветер {weather.windSpeedMps} м/с · осадки: {precipitationType?.name ?? `ID ${weather.precipitationTypeId}`}</p>}
    {capsule.occasion && <p>Повод: {capsule.occasion}</p>}
    {capsule.preferredStyle && <p>Стиль: {capsule.preferredStyle}</p>}
    {capsule.explanation && <div><h2>Почему этот набор</h2><p style={{ whiteSpace: "pre-wrap" }}>{capsule.explanation}</p></div>}
    {weather && precipitation.isError && <p className={styles.muted}>Не удалось загрузить название осадков.</p>}
  </section>;
}

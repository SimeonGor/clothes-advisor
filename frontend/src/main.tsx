import React from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { App } from "./app";

createRoot(document.getElementById("root")!).render(
  <React.StrictMode><BrowserRouter basename="/app"><App /></BrowserRouter></React.StrictMode>,
);

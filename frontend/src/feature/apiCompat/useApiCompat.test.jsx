import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import api from "../auth/api";
import { ToastProvider } from "../ui/Toast";
import useApiCompat from "./useApiCompat";

jest.mock("../auth/api", () => ({ __esModule: true, default: { get: jest.fn() } }));

const Probe = () => {
  useApiCompat();
  return null;
};

const mount = () => render(<ToastProvider><Probe /></ToastProvider>);

afterEach(() => jest.clearAllMocks());

it("asks the server which API it serves", async () => {
  api.get.mockResolvedValue({ data: { apiVersion: "1.0.0", apiMajors: [1] } });
  mount();
  await waitFor(() => expect(api.get).toHaveBeenCalledWith("/config"));
});

it("says nothing when they have a major in common", async () => {
  api.get.mockResolvedValue({ data: { apiVersion: "1.0.0", apiMajors: [1] } });
  mount();
  await waitFor(() => expect(api.get).toHaveBeenCalled());
  expect(screen.queryByText(/older than/)).toBeNull();
});

it("offers a reload when the server has moved past this page", async () => {
  api.get.mockResolvedValue({ data: { apiVersion: "99.0.0", apiMajors: [99] } });
  mount();
  expect(await screen.findByText(/This page is older than the server/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Reload" })).toBeInTheDocument();
});

it("says nothing when the server cannot be reached", async () => {
  api.get.mockRejectedValue(new Error("offline"));
  mount();
  await waitFor(() => expect(api.get).toHaveBeenCalled());
  expect(screen.queryByText(/older than/)).toBeNull();
});

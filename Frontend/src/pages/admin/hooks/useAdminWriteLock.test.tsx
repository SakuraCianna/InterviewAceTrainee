import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { useAdminWriteLock } from "./useAdminWriteLock";

function Harness({ submit }: { submit: () => Promise<void> }) {
  const { isLocked, runLocked } = useAdminWriteLock();
  return (
    <button type="button" disabled={isLocked("credit-adjust")} onClick={() => void runLocked("credit-adjust", submit)}>
      {isLocked("credit-adjust") ? "提交中" : "提交调整"}
    </button>
  );
}

describe("useAdminWriteLock", () => {
  it("同一管理写操作完成前只提交一次", async () => {
    let resolveSubmit: () => void = () => undefined;
    const submit = vi.fn(() => new Promise<void>((resolve) => {
      resolveSubmit = resolve;
    }));
    render(<Harness submit={submit} />);

    await userEvent.dblClick(screen.getByRole("button", { name: "提交调整" }));
    expect(submit).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "提交中" })).toBeDisabled();

    resolveSubmit();
  });

  it("上一次完成后允许相同内容作为新的明确操作再次提交", async () => {
    const submit = vi.fn().mockResolvedValue(undefined);
    render(<Harness submit={submit} />);

    await userEvent.click(screen.getByRole("button", { name: "提交调整" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "提交调整" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "提交调整" }));

    expect(submit).toHaveBeenCalledTimes(2);
  });
});

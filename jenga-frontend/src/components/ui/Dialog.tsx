import { useEffect, useRef, type ReactNode } from "react";

interface Props {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
}

/** Native <dialog>: focus trapping, Esc to close and inert background come for free. */
export function Dialog({ open, title, onClose, children }: Props) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (open && !el.open) el.showModal();
    if (!open && el.open) el.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      onClose={onClose}
      aria-labelledby="dialog-title"
      className="m-auto w-[min(92vw,30rem)] border border-rule bg-paper p-0 text-ink shadow-2xl"
    >
      <div className="border-b border-rule px-6 py-4">
        <h2 id="dialog-title" className="text-lg font-semibold">
          {title}
        </h2>
      </div>
      <div className="px-6 py-5">{children}</div>
    </dialog>
  );
}

import { useRegisterSW } from "virtual:pwa-register/react";

/*
  Service workers only register in secure contexts (https or localhost).
  On plain http the app still works; it just isn't installable.
*/
export function usePwaUpdate() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW({ immediate: true });

  return {
    needRefresh,
    reload: () => updateServiceWorker(true),
    dismiss: () => setNeedRefresh(false),
  };
}

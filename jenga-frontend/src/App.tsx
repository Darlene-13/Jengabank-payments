import { RouterProvider } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext";
import { ToastProvider } from "./context/ToastContext";
import { UpdatePrompt } from "./components/UpdatePrompt";
import { router } from "./routes/router";

export default function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <RouterProvider router={router} />
        <UpdatePrompt />
      </ToastProvider>
    </AuthProvider>
  );
}

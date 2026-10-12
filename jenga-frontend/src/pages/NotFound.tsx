import { Link } from "react-router-dom";

export default function NotFound() {
  return (
    <div className="py-16">
      <h1 className="text-3xl font-semibold tracking-tight">This page doesn't exist</h1>
      <p className="mt-2 text-muted">The link may be old, or the address has a typo.</p>
      <Link to="/" className="mt-6 inline-block text-note underline underline-offset-4">Go to your home page</Link>
    </div>
  );
}

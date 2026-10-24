import { StrictMode, Component, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles.css';
import './themes.css';
class ErrorBoundary extends Component<{children: ReactNode}, {failed: boolean}> { state = { failed: false }; static getDerivedStateFromError() { return { failed: true }; } render() { if (this.state.failed) return <main className="fatal-error"><h1>Tracksy couldn’t display this page.</h1><p>Reload to reopen your workspace. Your saved work is retained on the server.</p><button className="button" onClick={() => location.reload()}>Reload workspace</button></main>; return this.props.children; } }
createRoot(document.getElementById('root')!).render(<StrictMode><ErrorBoundary><App/></ErrorBoundary></StrictMode>);

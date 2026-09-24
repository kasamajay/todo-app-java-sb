import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import AdminApp from './admin/AdminApp.jsx'

const root = createRoot(document.getElementById('root'))
root.render(window.location.pathname.startsWith('/admin') ? <AdminApp /> : <App />)

import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080'
const API_KEY = import.meta.env.VITE_API_KEY || 'dynamq-admin-secret-key'

const api = axios.create({
    baseURL: API_BASE,
    timeout: 10000,
    headers: {
        'X-API-Key': API_KEY
    }
})

export default {
    // Dashboard
    getDashboard() {
        return api.get('/api/admin/dashboard')
    },

    // Clients
    getClients() {
        return api.get('/api/admin/clients')
    },

    getClientDetail(clientId) {
        return api.get(`/api/admin/clients/${clientId}`)
    },

    kickClient(clientId) {
        return api.delete(`/api/admin/clients/${clientId}`)
    },

    // Subscriptions
    getSubscriptions() {
        return api.get('/api/admin/subscriptions')
    },

    // Cluster
    getClusterNodes() {
        return api.get('/api/admin/cluster/nodes')
    },

    // ACL Rules
    getAclRules() {
        return api.get('/api/admin/acl/rules')
    },

    addAclRule(rule) {
        return api.post('/api/admin/acl/rules', rule)
    },

    deleteAclRule(ruleId) {
        return api.delete(`/api/admin/acl/rules/${ruleId}`)
    }
}

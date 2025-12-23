import { createRouter, createWebHistory } from 'vue-router'

const routes = [
    {
        path: '/',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue')
    },
    {
        path: '/clients',
        name: 'Clients',
        component: () => import('../views/Clients.vue')
    },

    {
        path: '/cluster',
        name: 'Cluster',
        component: () => import('../views/Cluster.vue')
    },
    {
        path: '/acl',
        name: 'AclRules',
        component: () => import('../views/AclRules.vue')
    },
    {
        path: '/routes',
        name: 'DataRoutes',
        component: () => import('../views/DataRoutes.vue')
    },
    {
        path: '/kafka',
        name: 'KafkaConfig',
        component: () => import('../views/KafkaConfig.vue')
    },
    {
        path: '/monitoring',
        name: 'Monitoring',
        component: () => import('../views/Monitoring.vue')
    }
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

export default router

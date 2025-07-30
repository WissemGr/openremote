# Render.com Deployment Guide for OpenRemote

## Fixed Issues
✅ Removed invalid `user` field from database configuration  
✅ Fixed `dockerImage` field - replaced with `dockerfilePath`  
✅ Created Keycloak Dockerfile for proper deployment  
✅ Added proper environment variables  

## Deployment Options

### Option 1: Full Stack (render.yaml)
Deploys both OpenRemote Manager and Keycloak together.

### Option 2: Simple Manager Only (render-simple.yaml)
Deploys only the OpenRemote Manager with basic authentication (no Keycloak).

### Option 3: Backend Only (render-backend-only.yaml)
Deploys only the OpenRemote Manager backend. You can deploy the frontend separately on Vercel.

## Steps to Deploy

### 1. Prepare Your Repository
```bash
# Make sure your code is pushed to GitHub
git add .
git commit -m "Add Render deployment configuration"
git push origin main
```

### 2. Create Render Account
- Go to [render.com](https://render.com)
- Sign up/login with your GitHub account
- Connect your repository

### 3. Deploy with Blueprint

#### Option A: Full Stack Deployment
1. In Render dashboard, click "New" → "Blueprint"
2. Connect your GitHub repository
3. Render will automatically detect `render.yaml`
4. Review the configuration and click "Apply"

#### Option B: Backend Only Deployment
1. Rename `render-backend-only.yaml` to `render.yaml`
2. Follow Option A steps
3. Deploy frontend separately on Vercel using the existing `vercel.json`

### 4. Configure Environment Variables
After deployment, you may need to update environment variables:

1. Go to your service in Render dashboard
2. Navigate to "Environment" tab
3. Update these variables:
   - `OR_HOSTNAME` - Set to your actual Render URL
   - `SETUP_ADMIN_PASSWORD` - Set a secure password
   - `KEYCLOAK_ADMIN_PASSWORD` - Set a secure password

### 5. Database Setup
The PostgreSQL database will be automatically created and connected.

### 6. Access Your Application
- Manager: `https://openremote-manager.onrender.com`
- Keycloak (if deployed): `https://openremote-keycloak.onrender.com`

## Troubleshooting

### Common Issues:

1. **Build Fails**
   - Check that `manager/Dockerfile` exists
   - Verify Docker build context in logs

2. **Database Connection Errors**
   - Database takes time to initialize
   - Check environment variables are properly set

3. **Service Health Check Fails**
   - Services may take 5-10 minutes to fully start
   - Check logs for detailed error messages

4. **Keycloak Deployment Issues**
   - Consider deploying Keycloak separately
   - Or use a managed identity service

### Alternative: Deploy Keycloak Separately

If Keycloak deployment fails, deploy it as a separate service:

1. Create new Web Service in Render
2. Use Docker image: `quay.io/keycloak/keycloak:22.0`
3. Set environment variables:
   ```
   KEYCLOAK_ADMIN=admin
   KEYCLOAK_ADMIN_PASSWORD=your_password
   KC_DB=postgres
   KC_DB_URL=<your_postgres_connection_string>
   KC_HOSTNAME=your-keycloak-service.onrender.com
   KC_HTTP_ENABLED=true
   ```
4. Update the manager service to point to the new Keycloak URL

## Cost Considerations

- **Starter Plan**: $7/month per service
- **Database**: $7/month for starter PostgreSQL
- **Total**: ~$21/month for full deployment

Free tier available but with limitations:
- Services spin down after 15 minutes of inactivity
- 750 hours/month limit

## Next Steps

1. Deploy using one of the configurations
2. Test the deployment
3. Configure your domain (optional)
4. Set up monitoring and backups
5. Configure SSL certificates (handled automatically by Render)

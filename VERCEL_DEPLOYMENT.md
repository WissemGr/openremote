# OpenRemote Vercel Deployment

This repository has been configured for deployment on Vercel. The deployment focuses on the OpenRemote Manager UI application.

## Deployment Configuration

### Files Added for Vercel:
- `vercel.json` - Vercel configuration file
- `build-vercel.sh` - Custom build script for the complex workspace
- `.vercelignore` - Files to exclude from deployment
- `VERCEL_DEPLOYMENT.md` - This documentation

### What Gets Deployed:
The OpenRemote Manager application (`ui/app/manager`) is deployed as a static frontend application.

## Environment Variables

You may need to configure the following environment variables in your Vercel dashboard:

- `MANAGER_URL` - URL of the OpenRemote manager backend (if different from default)
- `KEYCLOAK_URL` - URL of the Keycloak authentication server (if different from default)
- `NODE_OPTIONS` - Already set to `--max_old_space_size=4096` for build performance

## Build Process

The build process:
1. Installs dependencies with Yarn
2. Builds UI utility components
3. Builds required UI components (model, rest, or-app, shared)
4. Generates TypeScript models
5. Builds the manager application for production
6. Outputs static files to `ui/app/manager/dist`

## Local Testing

To test the build locally:
```bash
# Make sure the build script is executable
chmod +x build-vercel.sh

# Run the build
./build-vercel.sh

# Serve the built files (you can use any static server)
cd ui/app/manager/dist
python -m http.server 8000
```

## Deployment Steps

1. Push your code to GitHub
2. Connect your GitHub repository to Vercel
3. Vercel will automatically detect the `vercel.json` configuration
4. Set any required environment variables in the Vercel dashboard
5. Deploy!

## Notes

- This is a frontend-only deployment. You'll need a separate backend deployment for the OpenRemote manager and Keycloak.
- The application is configured as a Single Page Application (SPA) with client-side routing.
- Static assets are cached appropriately for performance.
- Security headers are configured for production use.

## Troubleshooting

If the build fails:
1. Check that all workspace dependencies are properly configured
2. Ensure Node.js memory limits are sufficient
3. Verify that the TypeScript model generation completes successfully
4. Check the Vercel build logs for specific error messages

For a simpler deployment, you might consider deploying individual UI applications separately rather than the entire workspace.

#!/usr/bin/env bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🚀 Setting up GitHub Actions OIDC for aws-lambda-streaming-jvm-runtime${NC}"
echo ""

# Defaults
DEFAULT_ORG="elenavanengelenmaslova"
DEFAULT_REPO="aws-lambda-streaming-jvm-runtime"
DEFAULT_REGION="eu-west-1"
STACK_NAME="streaming-example-github-oidc"
TEMPLATE_FILE="$(dirname "$0")/../deployment/aws/oidc/github-oidc-role.yaml"

# Get parameters
read -r -p "Enter your GitHub organization/username [$DEFAULT_ORG]: " GITHUB_ORG
GITHUB_ORG=${GITHUB_ORG:-$DEFAULT_ORG}

read -r -p "Enter your GitHub repository name [$DEFAULT_REPO]: " GITHUB_REPO
GITHUB_REPO=${GITHUB_REPO:-$DEFAULT_REPO}

read -r -p "Enter AWS region [$DEFAULT_REGION]: " AWS_REGION
AWS_REGION=${AWS_REGION:-$DEFAULT_REGION}

# Check if you already have a GitHub OIDC provider in this account
EXISTING_OIDC=""
OIDC_ARN=$(aws iam list-open-id-connect-providers --query 'OpenIDConnectProviderList[?ends_with(Arn, `token.actions.githubusercontent.com`)].Arn' --output text 2>/dev/null || true)
if [ -n "$OIDC_ARN" ] && [ "$OIDC_ARN" != "None" ]; then
  echo ""
  echo -e "${YELLOW}Found existing GitHub OIDC provider: $OIDC_ARN${NC}"
  read -r -p "Reuse this provider? (Y/n): " REUSE
  if [[ ! $REUSE =~ ^[Nn]$ ]]; then
    EXISTING_OIDC="$OIDC_ARN"
  fi
fi

echo ""
echo -e "${YELLOW}📋 Configuration:${NC}"
echo "  GitHub Org/User: $GITHUB_ORG"
echo "  GitHub Repository: $GITHUB_REPO"
echo "  AWS Region: $AWS_REGION"
echo "  Stack Name: $STACK_NAME"
echo "  Template: $TEMPLATE_FILE"
if [ -n "$EXISTING_OIDC" ]; then
  echo "  Reusing OIDC Provider: $EXISTING_OIDC"
fi
echo ""

read -r -p "Continue with deployment? (y/N): " CONFIRM
if [[ ! $CONFIRM =~ ^[Yy]$ ]]; then
  echo "Deployment cancelled."
  exit 0
fi

echo ""
echo -e "${BLUE}🔧 Deploying OIDC CloudFormation stack...${NC}"

# Build parameter overrides
PARAMS="GitHubOrg=$GITHUB_ORG GitHubRepo=$GITHUB_REPO"
if [ -n "$EXISTING_OIDC" ]; then
  PARAMS="$PARAMS ExistingOIDCProviderArn=$EXISTING_OIDC"
fi

# Deploy the CloudFormation stack
aws cloudformation deploy \
  --template-file "$TEMPLATE_FILE" \
  --stack-name "$STACK_NAME" \
  --parameter-overrides $PARAMS \
  --capabilities CAPABILITY_NAMED_IAM \
  --region "$AWS_REGION"

if [ $? -eq 0 ]; then
  echo ""
  echo -e "${GREEN}✅ OIDC setup completed successfully!${NC}"
  echo ""

  # Get outputs
  ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
  ROLE_ARN=$(aws cloudformation describe-stacks \
    --stack-name "$STACK_NAME" \
    --region "$AWS_REGION" \
    --query 'Stacks[0].Outputs[?OutputKey==`GitHubActionsRoleArn`].OutputValue' \
    --output text)
  ROLE_NAME=$(echo "$ROLE_ARN" | awk -F'/' '{print $NF}')

  echo -e "${YELLOW}📝 Next Steps:${NC}"
  echo ""
  echo "1. Add this SECRET to your GitHub repository settings:"
  echo "   Name:  AWS_ACCOUNT_ID"
  echo "   Value: $ACCOUNT_ID"
  echo ""
  echo "2. Add this VARIABLE to your GitHub repository settings:"
  echo "   Name:  OIDC_ROLE_NAME"
  echo "   Value: $ROLE_NAME"
  echo ""
  echo "3. Your GitHub Actions workflows will use this role:"
  echo "   Role ARN: $ROLE_ARN"
  echo ""
  echo -e "${GREEN}🎉 You're ready to deploy via GitHub Actions!${NC}"
  echo ""
  echo -e "${BLUE}💡 To deploy manually:${NC}"
  echo "   cd deployment/aws/sam"
  echo "   ./deploy.sh"
  echo ""
else
  echo -e "${RED}❌ OIDC setup failed. Check the error messages above.${NC}"
  exit 1
fi
